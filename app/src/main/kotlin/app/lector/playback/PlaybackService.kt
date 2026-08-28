package app.lector.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import app.lector.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A transport request arriving from outside the app: lock screen, a Bluetooth
 * headset, or a paired watch's media control surface. */
sealed interface PlaybackCommand {
    data class SetPlaying(val playing: Boolean) : PlaybackCommand
    data class Skip(val seconds: Float) : PlaybackCommand
}

/**
 * Hosts the [MediaSessionCompat] that makes Lector's reading show up as ordinary
 * media: lock-screen transport controls, Bluetooth media buttons, and — the
 * point of this branch — a paired watch's media control surface, which mirrors
 * whatever session is active on the phone rather than offering anything Lector
 * has to build itself.
 *
 * This service owns no reading state. [ReaderViewModel][app.lector.ReaderViewModel]
 * remains the single source of truth for the document, sentence position, and
 * speed — this class only relays OS-level transport events upward via
 * [commands] and mirrors playback state downward into the session via
 * [updateNowPlaying], so those surfaces show something accurate. The 30-second
 * skip amount itself is decided by [app.lector.core.PlaybackClock], which the
 * ViewModel calls in response to a [PlaybackCommand.Skip].
 *
 * Runs in the foreground while speaking. That is also what keeps the process
 * alive in the background — without it, Android eventually suspends TTS mid
 * book once the app leaves the foreground.
 */
class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val _commands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 4)
    val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "Lector").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = emit(PlaybackCommand.SetPlaying(true))
                override fun onPause() = emit(PlaybackCommand.SetPlaying(false))
                override fun onStop() = emit(PlaybackCommand.SetPlaying(false))
                // Both pairs are wired to the same 30s skip: which pair a given
                // media control surface renders (rewind/fast-forward vs
                // previous/next) is up to that surface, not something Lector
                // can pick — a watch's complication may only offer one pair.
                override fun onFastForward() = emit(PlaybackCommand.Skip(SKIP_SECONDS))
                override fun onRewind() = emit(PlaybackCommand.Skip(-SKIP_SECONDS))
                override fun onSkipToNext() = emit(PlaybackCommand.Skip(SKIP_SECONDS))
                override fun onSkipToPrevious() = emit(PlaybackCommand.Skip(-SKIP_SECONDS))
            })
            setPlaybackState(playbackState(isPlaying = false, canSkip = false))
            isActive = true
        }
    }

    private fun emit(command: PlaybackCommand) {
        _commands.tryEmit(command)
    }

    /**
     * Called by ReaderViewModel whenever playback-relevant state changes: a new
     * document is loaded, play/pause toggles, or the title changes. Cheap
     * enough to call unconditionally on every relevant state emission — this
     * just rebuilds two small compat objects, no I/O.
     */
    fun updateNowPlaying(title: String?, isPlaying: Boolean, canSkip: Boolean) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build(),
        )
        mediaSession.setPlaybackState(playbackState(isPlaying, canSkip))
        if (isPlaying) startForegroundNotification(title) else stopForegroundGracefully()
    }

    private fun playbackState(isPlaying: Boolean, canSkip: Boolean): PlaybackStateCompat {
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (canSkip) {
            actions = actions or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f,
            )
            .build()
    }

    private fun startForegroundNotification(title: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(getString(R.string.playback_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(MediaNotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /** Drops the "ongoing" pin and lets the system dismiss the notification, but
     * keeps the service (and session) alive so a paused book can resume from the
     * same media controls without losing them. */
    private fun stopForegroundGracefully() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_notification_channel),
            NotificationManager.IMPORTANCE_LOW, // no sound/heads-up for a reading session
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        /** How far a rewind/fast-forward or skip-prev/next control moves. */
        const val SKIP_SECONDS = 30f

        private const val CHANNEL_ID = "lector_playback"
        private const val NOTIFICATION_ID = 1
    }
}
