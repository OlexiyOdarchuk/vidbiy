package ua.vidbiy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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

    // Музика чи білий шум на час сигналу стають на паузу; сигнал лунає, навіть якщо фокус не дали.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .build()

    private var player: MediaPlayer? = null
    private var ramp: Job? = null
    private var savedVolume = -1
    private var level = MIN_VOLUME
    private var ducked = false

    /** Під час голосового оголошення мелодія звучить тихіше. */
    fun duck(on: Boolean) {
        ducked = on
        applyVolume()
    }

    private fun applyVolume() {
        val v = level * if (ducked) DUCK else 1f
        try {
            player?.setVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        level = MIN_VOLUME
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) {
            savedVolume = -1
        }

        audio.requestAudioFocus(focus)
        player = createPlayer()
        ramp = scope.launch {
            for (step in 0..RAMP_STEPS) {
                level = MIN_VOLUME + (1f - MIN_VOLUME) * step / RAMP_STEPS
                applyVolume()
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
        audio.abandonAudioFocusRequest(focus)
        vibrator.cancel()
        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: SecurityException) {
            }
            savedVolume = -1
        }
    }

    private fun createPlayer(from: Int = 0): MediaPlayer? {
        // Якщо обраний звук не відкрився (файл видалили, мелодію прибрали), звучить запасний.
        val candidates = Sounds.candidates(context, Prefs(context))
        for (i in from until candidates.size) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(context, candidates[i])
                mp.isLooping = true
                mp.setVolume(MIN_VOLUME, MIN_VOLUME)
                // Звук обірвався посеред сигналу — переходимо до наступного, а не мовчимо.
                mp.setOnErrorListener { failed, _, _ ->
                    if (player === failed) {
                        failed.release()
                        player = createPlayer(i + 1)
                        applyVolume()
                    }
                    true
                }
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
        const val DUCK = 0.25f
        const val RAMP_STEPS = 20
        const val RAMP_STEP_MS = 3_000L
    }
}
