package com.sync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives media control intents from notification buttons
 * and relays them to MainActivity's WebView via JS evaluation.
 */
class MediaActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activity = MainActivity.instance ?: return
        when (intent.action) {
            MediaPlaybackService.ACTION_PLAY,
            MediaPlaybackService.ACTION_PAUSE -> {
                activity.evaluateJs("if(typeof togglePlay==='function')togglePlay()")
            }
            MediaPlaybackService.ACTION_NEXT -> {
                activity.evaluateJs("if(typeof playNext==='function')playNext()")
            }
            MediaPlaybackService.ACTION_PREV -> {
                activity.evaluateJs("if(typeof playPrev==='function')playPrev()")
            }
        }
    }
}
