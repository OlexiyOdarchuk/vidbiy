package ua.vidbiy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var player: MediaPlayer? = null
    private var ramp: Job? = null
    private var savedVolume = -1

    fun start(scope: CoroutineScope) {
        stop()
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) {
            savedVolume = -1
        }

        player = createPlayer()
        ramp = scope.launch {
            for (step in 0..RAMP_STEPS) {
                val v = MIN_VOLUME + (1f - MIN_VOLUME) * step / RAMP_STEPS
                player?.setVolume(v, v)
                delay(RAMP_STEP_MS)
            }
        }

        @Suppress("DEPRECATION")
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
    }

    fun stop() {
        ramp?.cancel()
        ramp = null
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        player = null
        vibrator.cancel()
        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: SecurityException) {
            }
            savedVolume = -1
        }
    }

    private fun createPlayer(): MediaPlayer? {
        val candidates =
            listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
                .mapNotNull { RingtoneManager.getDefaultUri(it) }
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.setVolume(MIN_VOLUME, MIN_VOLUME)
                mp.prepare()
                mp.start()
                return mp
            } catch (_: Exception) {
                mp.release()
            }
        }
        return null
    }

    private companion object {
        const val MIN_VOLUME = 0.15f
        const val RAMP_STEPS = 20
        const val RAMP_STEP_MS = 3_000L
    }
}
