package com.familyconnect.app.safety

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

object EmergencyAudio {
    private var player: MediaPlayer? = null

    @Synchronized
    fun start(context: Context) {
        if (player?.isPlaying == true) return
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer().apply {
            setDataSource(context.applicationContext, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            setVolume(1f, 1f)
            prepare()
            start()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
