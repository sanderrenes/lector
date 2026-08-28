package app.lector

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.lector.core.Document
import app.lector.core.PlaybackClock
import app.lector.core.Segmenter
import app.lector.data.LibraryEntry
import app.lector.data.LibraryStore
import app.lector.io.DocumentImporter
import app.lector.io.ImportResult
import app.lector.playback.PlaybackCommand
import app.lector.playback.PlaybackService
import app.lector.speech.EngineStatus
import app.lector.speech.SpeechEngine
import app.lector.speech.SpeechPosition
import app.lector.speech.SystemTtsEngine
import app.lector.speech.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, LIBRARY, READER }

data class ReaderUiState(
    val screen: Screen = Screen.HOME,
    val document: Document? = null,
    val title: String? = null,
    val engineStatus: EngineStatus = EngineStatus.INITIALIZING,
    val isPlaying: Boolean = false,
    val currentSentence: Int = 0,
    val wordStart: Int = -1,
    val wordEnd: Int = -1,
    val speed: Float = 1.0f,
    val voices: List<VoiceOption> = emptyList(),
    val selectedVoiceId: String? = null,
    val library: List<LibraryEntry> = emptyList(),
    val recents: List<LibraryEntry> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val busy: Boolean = false,
    val importError: String? = null,
    val sleepMinutes: Int = 0, // 0 = off
) {
    val engineReady: Boolean get() = engineStatus == EngineStatus.READY
}

class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val segmenter = Segmenter()
    private val engine: SpeechEngine = SystemTtsEngine(application)
    private val importer = DocumentImporter(application)
    private val library = LibraryStore(application)

    private val _uiState = MutableStateFlow(
        ReaderUiState(currentSentence = savedState[KEY_SENTENCE] ?: 0),
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentUri: String? = null
    private var lastSavedSentence = 0
    private var sleepJob: kotlinx.coroutines.Job? = null

    // ── Media session (watch/lock-screen/Bluetooth transport controls) ─────────
    // Bound for the ViewModel's whole lifetime so PlaybackService.commands can be
    // collected as soon as it connects; the service itself isn't marked "started"
    // (and so doesn't go foreground or outlive an unbind) until playback actually
    // begins — see setPlaying().
    private var playbackService: PlaybackService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.LocalBinder)?.service ?: return
            playbackService = service
            viewModelScope.launch {
                service.commands.collect { command ->
                    when (command) {
                        is PlaybackCommand.SetPlaying -> setPlaying(command.playing)
                        is PlaybackCommand.Skip -> skipSeconds(command.seconds)
                    }
                }
            }
            pushNowPlaying(_uiState.value)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    init {
        getApplication<Application>().bindService(
            Intent(getApplication(), PlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        viewModelScope.launch {
            // Position/word-highlight updates fire many times a second while
            // speaking; only a change to what the session actually shows should
            // touch it (each push re-issues startForeground(), not something to
            // do on every word boundary).
            _uiState
                .map { Triple(it.title, it.isPlaying, it.document != null) }
                .distinctUntilChanged()
                .collect { (title, isPlaying, hasDoc) ->
                    playbackService?.updateNowPlaying(title, isPlaying, canSkip = hasDoc)
                }
        }
        viewModelScope.launch {
            engine.status.collect { s -> _uiState.update { it.copy(engineStatus = s) } }
        }
        viewModelScope.launch {
            engine.speaking.collect { p -> _uiState.update { it.copy(isPlaying = p) } }
        }
        viewModelScope.launch {
            engine.voices.collect { v -> _uiState.update { it.copy(voices = v) } }
        }
        viewModelScope.launch {
            engine.selectedVoiceId.collect { id -> _uiState.update { it.copy(selectedVoiceId = id) } }
        }
        viewModelScope.launch {
            engine.position.collect { pos: SpeechPosition? ->
                if (pos != null) {
                    savedState[KEY_SENTENCE] = pos.sentenceIndex
                    _uiState.update {
                        it.copy(currentSentence = pos.sentenceIndex, wordStart = pos.wordStart, wordEnd = pos.wordEnd)
                    }
                    maybePersistResume(pos.sentenceIndex)
                } else {
                    _uiState.update { it.copy(wordStart = -1, wordEnd = -1) }
                }
            }
        }
        viewModelScope.launch {
            engine.finished.collect { finished ->
                if (finished) {
                    savedState[KEY_SENTENCE] = 0
                    currentUri?.let { uri -> library.saveResume(uri, 0) }
                    _uiState.update { it.copy(currentSentence = 0, wordStart = -1, wordEnd = -1) }
                }
            }
        }
        viewModelScope.launch {
            library.entries.collect {
                _uiState.update { s -> s.copy(library = library.all(), recents = library.recents()) }
            }
        }
        viewModelScope.launch { library.load() }
    }

    // ── Text / documents ──────────────────────────────────────────────────────

    private fun setText(text: String, title: String? = null, startSentence: Int = 0) {
        engine.stop()
        viewModelScope.launch {
            val doc = withContext(Dispatchers.Default) { segmenter.segment(text) }
            val start = if (doc.isEmpty) 0 else startSentence.coerceIn(0, doc.sentences.lastIndex)
            lastSavedSentence = start
            savedState[KEY_SENTENCE] = start
            _uiState.update {
                it.copy(
                    document = if (doc.isEmpty) null else doc,
                    title = title,
                    screen = if (doc.isEmpty) it.screen else Screen.READER,
                    currentSentence = start,
                    wordStart = -1, wordEnd = -1,
                )
            }
        }
    }

    /** Pasted/shared text has no backing file, so it isn't a library entry. */
    fun setPastedText(text: String) {
        currentUri = null
        setText(text, title = null, startSentence = 0)
    }

    /** Open a document from the file picker or library; resumes its saved position. */
    fun openUri(uri: Uri) {
        engine.stop()
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, importError = null) }
            when (val result = importer.import(uri)) {
                is ImportResult.Failed -> _uiState.update { it.copy(busy = false, importError = result.message) }
                is ImportResult.Ok -> {
                    _uiState.update { it.copy(busy = false) }
                    val doc = result.doc
                    val key = uri.toString()
                    currentUri = key
                    importer.rememberFile(uri) // survive reboot in Recents (F1)
                    library.markOpened(key, doc.title, doc.format, System.currentTimeMillis())
                    setText(doc.text, doc.title, startSentence = library.resumeFor(key))
                }
            }
        }
    }

    // ── Folder library ────────────────────────────────────────────────────────

    fun chooseFolder(treeUri: Uri) {
        importer.rememberFolder(treeUri)
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true, scanMessage = "Scanning folder…", screen = Screen.LIBRARY) }
            val items = importer.scanFolderRecursive(treeUri)
            library.addDiscovered(
                items.map { Triple(it.uri.toString(), it.name, it.name.substringAfterLast('.', "")) },
                System.currentTimeMillis(),
            )
            _uiState.update {
                it.copy(scanning = false, scanMessage = "Added ${items.size} document(s) to your library.")
            }
        }
    }

    fun showLibrary() { engine.stop(); _uiState.update { it.copy(screen = Screen.LIBRARY, document = null) } }
    fun showHome() { engine.stop(); _uiState.update { it.copy(screen = Screen.HOME, document = null) } }
    fun dismissImportError() = _uiState.update { it.copy(importError = null) }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun selectVoice(id: String?) = engine.selectVoice(id)

    fun playPause() = setPlaying(!_uiState.value.isPlaying)

    /**
     * Idempotent play/pause, shared by the on-screen button and the media-session
     * callback: a hardware onPlay() arriving while already playing (some Bluetooth
     * remotes send it redundantly) is a no-op rather than a toggle that would
     * pause instead.
     */
    private fun setPlaying(playing: Boolean) {
        val state = _uiState.value
        if (playing == state.isPlaying) return
        if (playing) {
            val doc = state.document ?: return
            // Marks the service "started" so it (and the media session/notification
            // it hosts) survives even if the app's task is later removed while this
            // keeps playing — see PlaybackService's class doc. Must happen right
            // before the service's own startForeground() call (from the state
            // collector's pushNowPlaying, triggered by engine.speaking below) to
            // stay inside Android's post-startForegroundService window.
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            engine.speak(doc, state.currentSentence, state.speed)
        } else {
            engine.stop()
            persistResumeNow(state.currentSentence)
        }
    }

    fun seekTo(sentenceIndex: Int) {
        val state = _uiState.value
        val doc = state.document ?: return
        if (doc.isEmpty) return
        val index = sentenceIndex.coerceIn(0, doc.sentences.lastIndex)
        if (state.isPlaying) engine.speak(doc, index, state.speed)
        savedState[KEY_SENTENCE] = index
        _uiState.update { it.copy(currentSentence = index, wordStart = -1, wordEnd = -1) }
        persistResumeNow(index)
    }

    fun skip(delta: Int) = seekTo(_uiState.value.currentSentence + delta)

    /**
     * Skip forward/backward by an estimated amount of listening time (negative =
     * backward) — what a media control's rewind/fast-forward or previous/next
     * button sends. See [PlaybackClock] for how "30 seconds" becomes a sentence.
     */
    fun skipSeconds(seconds: Float) {
        val state = _uiState.value
        val doc = state.document ?: return
        if (doc.isEmpty) return
        seekTo(PlaybackClock.skip(doc.sentences, state.currentSentence, seconds, state.speed))
    }

    fun setSpeed(speed: Float) {
        val state = _uiState.value
        _uiState.update { it.copy(speed = speed) }
        if (state.isPlaying && state.document != null) engine.speak(state.document, state.currentSentence, speed)
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _uiState.update { it.copy(sleepMinutes = minutes) }
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            engine.stop()
            persistResumeNow(_uiState.value.currentSentence)
            _uiState.update { it.copy(sleepMinutes = 0) }
        }
    }

    // ── Resume persistence ────────────────────────────────────────────────────

    private fun maybePersistResume(sentence: Int) {
        // Throttle disk writes: save at most every RESUME_STEP sentences during playback.
        if (kotlin.math.abs(sentence - lastSavedSentence) >= RESUME_STEP) persistResumeNow(sentence)
    }

    private fun persistResumeNow(sentence: Int, flush: Boolean = false) {
        lastSavedSentence = sentence
        val uri = currentUri ?: return
        library.saveResume(uri, sentence, flush) // per-key, synchronous — no coroutine needed
    }

    // ── Media session mirroring ─────────────────────────────────────────────────

    /** Keep the media session's transport state in sync with what's actually
     * happening, so a lock screen/watch control never shows a stale play button. */
    private fun pushNowPlaying(state: ReaderUiState) {
        playbackService?.updateNowPlaying(state.title, state.isPlaying, canSkip = state.document != null)
    }

    override fun onCleared() {
        // flush=true (commit) because viewModelScope is already cancelled here (F3).
        persistResumeNow(_uiState.value.currentSentence, flush = true)
        engine.shutdown()
        runCatching { getApplication<Application>().unbindService(serviceConnection) }
    }

    private companion object {
        const val KEY_SENTENCE = "lector.currentSentence"
        const val RESUME_STEP = 10
    }
}
