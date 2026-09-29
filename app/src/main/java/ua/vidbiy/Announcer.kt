package ua.vidbiy

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Голосове оголошення під час сигналу: причина й поточний час, раз на пів хвилини. */
class Announcer(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    private var ready = false
    private var job: Job? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && setUkrainian()
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = onSpeaking(true)
            override fun onDone(id: String?) = onSpeaking(false)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) = onSpeaking(false)

            override fun onError(id: String?, errorCode: Int) = onSpeaking(false)
        })
    }

    private fun setUkrainian(): Boolean {
        val r = tts.setLanguage(UKRAINIAN)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun start(scope: CoroutineScope, reason: String) {
        stop()
        job = scope.launch {
            delay(FIRST_MS)
            while (true) {
                if (ready) {
                    val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    tts.speak("$reason. Зараз $now.", TextToSpeech.QUEUE_FLUSH, null, "alarm")
                }
                delay(EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (ready) tts.stop()
        onSpeaking(false)
    }

    fun release() {
        stop()
        tts.shutdown()
    }

    companion object {
        private val UKRAINIAN = Locale.forLanguageTag("uk-UA")
        private const val FIRST_MS = 3_000L
        private const val EVERY_MS = 30_000L

        /** Чи є на телефоні український голос. Відповідь приходить асинхронно. */
        fun checkUkrainian(context: Context, onResult: (Boolean) -> Unit) {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                val ok = status == TextToSpeech.SUCCESS && tts?.isLanguageAvailable(UKRAINIAN)?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
                tts?.shutdown()
                onResult(ok)
            }
        }
    }
}
