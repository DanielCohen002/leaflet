package com.djcohen.offlinereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * Plays an audiobook in the background with a notification and lock-screen controls. The player screen binds to it to
 * show and change what is playing. Position, speed and bookmarks are saved through [AudioLibrary].
 */
class AudioService : Service() {
    inner class LocalBinder : Binder() { val service: AudioService get() = this@AudioService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var lib: AudioLibrary
    private lateinit var audio: AudioManager
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek = 0L
    private var wantPlay = false
    private var resumeAfterFocus = false
    /** Set when something else interrupted playback; the next play() steps back a few seconds so nothing is missed. */
    private var rewindOnPlay = false
    private val rewindMs get() = lib.reopenRewindSec * 1000L          // reopened from fully closed
    private val interruptRewindMs get() = lib.interruptRewindSec * 1000L  // interrupted by a notification, call or another app
    private var focusRequest: AudioFocusRequest? = null
    private var inForeground = false
    private var pausedForSilence = false
    private var silencedAt = 0L
    private var lastVolume = 0
    private var ticks = 0

    var book: Audiobook? = null
        private set
    var trackIndex = 0
        private set
    var speed = 1f
        private set
    /** The screen to refresh when anything about the playback changes. */
    var onChange: (() -> Unit)? = null

    val isPlaying: Boolean get() = wantPlay

    override fun onCreate() {
        super.onCreate()
        lib = AudioLibrary(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val channel = NotificationChannel(CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        session = MediaSession(this, "Leaflet audiobook").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = nextTrack()
                override fun onSkipToPrevious() = previousTrack()
                override fun onFastForward() = skip(lib.skipForwardSec * 1000L)
                override fun onRewind() = skip(-lib.skipBackSec * 1000L)
                override fun onSeekTo(pos: Long) = seekOverall(pos)
            })
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        registerReceiver(volumeWatcher, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), RECEIVER_NOT_EXPORTED)
    }

    /** Volume turned down to zero pauses the audiobook; turning it back up (within half an hour) resumes it. */
    private val volumeWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (v == 0 && lastVolume > 0 && wantPlay) {
                pause()
                pausedForSilence = true
                silencedAt = System.currentTimeMillis()
            } else if (v > 0 && pausedForSilence) {
                pausedForSilence = false
                if (System.currentTimeMillis() - silencedAt < 30 * 60 * 1000L) play()
            }
            lastVolume = v
        }
    }

    /** Headphones unplugged or Bluetooth lost: stop, rather than playing out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (wantPlay) pause() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (wantPlay) pause() else play()
            ACTION_BACK -> skip(-lib.skipBackSec * 1000L)
            ACTION_FORWARD -> skip(lib.skipForwardSec * 1000L)
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        saveNow()
        try { unregisterReceiver(noisy) } catch (_: Throwable) { }
        try { unregisterReceiver(volumeWatcher) } catch (_: Throwable) { }
        releasePlayer()
        session.release()
        abandonFocus()
        super.onDestroy()
    }

    // --- loading ----------------------------------------------------------------------------

    /** Make [b] the current audiobook, at its remembered place. Does nothing if it already is. */
    fun open(b: Audiobook) {
        if (book?.id == b.id) return
        saveNow()
        cancelSleep()
        wantPlay = false
        book = b
        speed = lib.speed(b.id)
        val (t, ms) = lib.position(b.id)
        val idx = t.coerceIn(0, b.tracks.size - 1)
        prepareTrack(idx, if (idx == t) (ms - rewindMs).coerceAtLeast(0) else 0L)  // reopened: step back a little
        updateSession()
        onChange?.invoke()
    }

    private fun prepareTrack(index: Int, ms: Long) {
        val b = book ?: return
        releasePlayer()
        trackIndex = index
        pendingSeek = ms.coerceAtLeast(0)
        prepared = false
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        try {
            mp.setDataSource(this, Uri.parse(b.tracks[index].uri))
        } catch (t: Throwable) {
            fail(b.tracks[index].name)
            return
        }
        mp.setOnPreparedListener {
            if (player !== mp) return@setOnPreparedListener
            prepared = true
            if (pendingSeek > 0) mp.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST)
            if (wantPlay) startPlayer()
            updateSession()
            onChange?.invoke()
        }
        mp.setOnCompletionListener {
            if (player !== mp) return@setOnCompletionListener
            if (trackIndex < b.tracks.size - 1) prepareTrack(trackIndex + 1, 0)
            else {
                // The end of the audiobook.
                wantPlay = false
                lib.savePosition(b.id, trackIndex, b.tracks[trackIndex].durationMs)
                abandonFocus()
                leaveForeground()
                updateSession()
                onChange?.invoke()
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            if (player === mp) fail(b.tracks[index].name)
            true
        }
        mp.prepareAsync()
    }

    private fun fail(name: String) {
        Toast.makeText(this, "Can't play $name. Is the file still there?", Toast.LENGTH_LONG).show()
        wantPlay = false
        abandonFocus()
        leaveForeground()
        updateSession()
        onChange?.invoke()
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        prepared = false
        try { mp.release() } catch (_: Throwable) { }
    }

    // --- transport --------------------------------------------------------------------------

    fun play() {
        val b = book ?: return
        pausedForSilence = false
        if (wantPlay) return
        // Pressing play at the very end starts the audiobook again.
        val atEnd = trackIndex == b.tracks.size - 1 && prepared && positionMs() >= b.tracks[trackIndex].durationMs - 1500
        if (atEnd) prepareTrack(0, 0)
        if (!requestFocus()) return
        if (rewindOnPlay) { rewindOnPlay = false; if (!atEnd) seekOverall((overallMs() - interruptRewindMs).coerceAtLeast(0)) }
        wantPlay = true
        try { startService(Intent(this, AudioService::class.java)) } catch (_: Throwable) { }
        enterForeground()
        if (prepared) startPlayer()
        updateSession()
        onChange?.invoke()
    }

    private fun startPlayer() {
        val mp = player ?: return
        try {
            mp.start()
            applySpeed()
        } catch (_: Throwable) { return }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    fun pause(keepFocus: Boolean = false) {
        if (!keepFocus) resumeAfterFocus = false
        pausedForSilence = false
        if (!wantPlay) return
        wantPlay = false
        try { if (prepared) player?.pause() } catch (_: Throwable) { }
        saveNow()
        if (!keepFocus) abandonFocus()
        leaveForeground()
        updateSession()
        onChange?.invoke()
    }

    fun toggle() = if (wantPlay) pause() else play()

    private fun stopAll() {
        pause()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    fun positionMs(): Long = if (prepared) (player?.currentPosition ?: 0).toLong() else pendingSeek

    fun overallMs(): Long = (book?.offsetOf(trackIndex) ?: 0L) + positionMs()

    fun skip(deltaMs: Long) = seekOverall(overallMs() + deltaMs)

    /** Jump to [ms] counted from the start of the whole audiobook. */
    fun seekOverall(ms: Long) {
        val b = book ?: return
        var rest = ms.coerceIn(0, b.totalMs)
        var idx = 0
        while (idx < b.tracks.size - 1 && rest >= b.tracks[idx].durationMs) { rest -= b.tracks[idx].durationMs; idx++ }
        seekTo(idx, rest)
    }

    /** Jump to [ms] inside track [index]. */
    fun seekTo(index: Int, ms: Long) {
        val b = book ?: return
        val idx = index.coerceIn(0, b.tracks.size - 1)
        val pos = ms.coerceIn(0, (b.tracks[idx].durationMs - 500).coerceAtLeast(0))
        if (idx == trackIndex && prepared) {
            try { player?.seekTo(pos, MediaPlayer.SEEK_CLOSEST) } catch (_: Throwable) { }
        } else if (idx == trackIndex) {
            pendingSeek = pos
        } else {
            prepareTrack(idx, pos)
        }
        saveAt(idx, pos)
        updateSession()
        onChange?.invoke()
    }

    private fun chapters(): List<Chapter> = book?.let { lib.cachedChapters(it.id) }.orEmpty()

    /** The next chapter if the audiobook has chapters, otherwise the next part. */
    fun nextTrack() {
        val b = book ?: return
        val now = overallMs()
        chapters().firstOrNull { it.ms > now + 500 }?.let { seekOverall(it.ms); return }
        if (trackIndex < b.tracks.size - 1) seekTo(trackIndex + 1, 0) else skip(lib.skipForwardSec * 1000L)
    }

    /** Back to the start of this chapter (or part); pressed again near the start, the one before. */
    fun previousTrack() {
        val now = overallMs()
        val chapters = chapters()
        if (chapters.isNotEmpty()) {
            val start = chapters.lastOrNull { it.ms <= now }?.ms ?: 0L
            if (now - start > 3000) { seekOverall(start); return }
            seekOverall(chapters.lastOrNull { it.ms < start }?.ms ?: 0L)
            return
        }
        if (positionMs() > 3000 || trackIndex == 0) seekTo(trackIndex, 0) else seekTo(trackIndex - 1, 0)
    }

    // --- sleep timer ------------------------------------------------------------------------

    private var sleepEnd = 0L            // SystemClock.elapsedRealtime() when a timed sleep ends, 0 = none
    private var sleepAtOverall = -1L     // where "end of chapter/part" stops, -1 = none

    /** Stop after [minutes] of listening time on the clock. */
    fun sleepIn(minutes: Int) {
        sleepEnd = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepAtOverall = -1
        onChange?.invoke()
    }

    /** Stop where the current chapter (or part, if there are no chapters) ends. */
    fun sleepAtEndOfSection() {
        val b = book ?: return
        val now = overallMs()
        val nextChapter = chapters().firstOrNull { it.ms > now + 500 }?.ms
        sleepAtOverall = nextChapter ?: (b.offsetOf(trackIndex) + b.tracks[trackIndex].durationMs)
        sleepEnd = 0
        onChange?.invoke()
    }

    fun cancelSleep() {
        sleepEnd = 0; sleepAtOverall = -1
        handler.removeCallbacks(sleepStop)
        onChange?.invoke()
    }

    /** Text for the player's sleep button, or null when no timer is set. */
    fun sleepLabel(): String? = when {
        sleepEnd > 0 -> AudioLibrary.short((sleepEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        sleepAtOverall >= 0 -> "end of " + if (chapters().isNotEmpty()) "chapter" else "part"
        else -> null
    }

    private val sleepStop = Runnable {
        if (sleepAtOverall < 0) return@Runnable
        val target = sleepAtOverall
        cancelSleep()
        pause()
        seekOverall(target)     // so that playing again starts the next chapter, not the last instant of this one
    }

    private fun checkSleep() {
        if (sleepEnd > 0 && SystemClock.elapsedRealtime() >= sleepEnd) { cancelSleep(); pause(); return }
        if (sleepAtOverall >= 0) {
            val remaining = sleepAtOverall - overallMs()
            if (remaining <= 1200L * speed + 200) {
                handler.removeCallbacks(sleepStop)
                handler.postDelayed(sleepStop, (remaining / speed).toLong().coerceAtLeast(0))
            }
        }
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 4f)
        book?.let { lib.setSpeed(it.id, speed) }
        if (wantPlay && prepared) applySpeed()
        updateSession()
        onChange?.invoke()
    }

    private fun applySpeed() {
        try { player?.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Throwable) { }
    }

    // --- saving -----------------------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (!wantPlay) return
            checkSleep()
            if (!wantPlay) return
            if (++ticks % 5 == 0) saveNow()
            handler.postDelayed(this, 1000)
        }
    }

    private fun saveNow() {
        val b = book ?: return
        if (prepared) lib.savePosition(b.id, trackIndex, positionMs())
    }

    private fun saveAt(index: Int, ms: Long) {
        book?.let { lib.savePosition(it.id, index, ms) }
    }

    // --- audio focus ------------------------------------------------------------------------

    private fun requestFocus(): Boolean {
        // Reuse the registered request (kept across a transient loss) so the GAIN callback still arrives.
        focusRequest?.let { return audio.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { if (wantPlay) rewindOnPlay = true; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (wantPlay) { pause(keepFocus = true); resumeAfterFocus = true; rewindOnPlay = true }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocus) { resumeAfterFocus = false; play() }
                }
            }, handler)
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // --- notification and lock screen -------------------------------------------------------

    private fun updateSession() {
        val b = book ?: return
        val track = b.tracks.getOrNull(trackIndex)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, b.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (b.tracks.size > 1) "Part ${trackIndex + 1} of ${b.tracks.size}" else (track?.name ?: ""))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, b.totalMs)
                .apply { lib.loadCover(b)?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build(),
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (wantPlay) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, overallMs(), if (wantPlay) speed else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
        if (inForeground) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun action(icon: Int, label: String, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, code, Intent(this, AudioService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()

    private fun notification(): Notification {
        val b = book
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).putExtra(AudioPlayerActivity.EXTRA_ID, b?.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(b?.title ?: "Leaflet")
            .setContentText(b?.tracks?.getOrNull(trackIndex)?.let { if (b.tracks.size > 1) "Part ${trackIndex + 1} of ${b.tracks.size}" else it.name } ?: "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(wantPlay)
            .setDeleteIntent(PendingIntent.getService(this, 4, Intent(this, AudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLargeIcon(b?.let { lib.loadCover(it) })
            .addAction(action(R.drawable.ic_replay, "Back", ACTION_BACK, 1))
            .addAction(
                if (wantPlay) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 2)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE, 2),
            )
            .addAction(action(R.drawable.ic_forward, "Forward", ACTION_FORWARD, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun enterForeground() {
        try {
            startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            inForeground = true
        } catch (_: Throwable) {
            // Not allowed to start from the background right now: playback still works, only without the notification.
        }
    }

    /** Paused: keep the notification (so it can resume) but let it be swiped away. */
    private fun leaveForeground() {
        if (!inForeground) return
        stopForeground(STOP_FOREGROUND_DETACH)
        inForeground = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7
        const val ACTION_TOGGLE = "com.djcohen.leaflet.TOGGLE"
        const val ACTION_BACK = "com.djcohen.leaflet.BACK"
        const val ACTION_FORWARD = "com.djcohen.leaflet.FORWARD"
        const val ACTION_STOP = "com.djcohen.leaflet.STOP"
    }
}
