package com.sync.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import java.net.URL

/**
 * MediaBrowserServiceCompat — registers the app as a music app.
 * Provides lockscreen / notification media controls.
 * The actual audio plays inside WebView (YouTube IFrame).
 * This service handles the media session token and notification UI.
 */
class MediaPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val CHANNEL_ID = "sync_media"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.sync.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.sync.app.ACTION_PAUSE"
        const val ACTION_NEXT = "com.sync.app.ACTION_NEXT"
        const val ACTION_PREV = "com.sync.app.ACTION_PREV"

        // Singleton ref so MainActivity can update it
        var instance: MediaPlaybackService? = null
    }

    private lateinit var mediaSession: MediaSession
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService() = this@MediaPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        mediaSession = MediaSession(this, "SYNCMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                    )
                    .setState(PlaybackState.STATE_NONE, 0, 1f)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { broadcastAction(ACTION_PLAY) }
                override fun onPause() { broadcastAction(ACTION_PAUSE) }
                override fun onSkipToNext() { broadcastAction(ACTION_NEXT) }
                override fun onSkipToPrevious() { broadcastAction(ACTION_PREV) }
            })
            isActive = true
        }

        sessionToken = androidx.media.session.MediaSessionCompat.Token
            .fromToken(mediaSession.sessionToken)
    }

    override fun onBind(intent: Intent): IBinder {
        return if (intent.action == SERVICE_INTERFACE) super.onBind(intent)!! else binder
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: android.os.Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long = 0, duration: Long = 0) {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )
                .setState(state, position, 1f)
                .build()
        )
    }

    fun updateMetadata(title: String, artist: String, albumArtUrl: String?) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "SYNC")

        mediaSession.setMetadata(builder.build())

        // Load album art async and update notification
        Thread {
            val bmp = try {
                albumArtUrl?.let { url ->
                    BitmapFactory.decodeStream(URL(url).openStream())
                }
            } catch (e: Exception) { null }

            val updatedBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "SYNC")
            if (bmp != null) {
                updatedBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            }
            mediaSession.setMetadata(updatedBuilder.build())

            showNotification(title, artist, bmp, mediaSession.controller.playbackState?.state == PlaybackState.STATE_PLAYING)
        }.start()
    }

    private fun showNotification(title: String, artist: String, art: Bitmap?, isPlaying: Boolean) {
        val launchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionPi(action: String, reqCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                this, reqCode,
                Intent(this, MediaActionReceiver::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(art)
            .setContentIntent(launchIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                android.R.drawable.ic_media_previous, "이전",
                actionPi(ACTION_PREV, 1)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "일시정지" else "재생",
                actionPi(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 2)
            )
            .addAction(
                android.R.drawable.ic_media_next, "다음",
                actionPi(ACTION_NEXT, 3)
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcastAction(action: String) {
        sendBroadcast(Intent(this, MediaActionReceiver::class.java).apply { this.action = action })
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mediaSession.release()
    }
}
