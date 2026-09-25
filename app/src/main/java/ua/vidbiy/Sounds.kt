package ua.vidbiy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/** Вбудований звук; файли синтезує tools/make_sounds.py у res/raw. */
data class Sound(val id: String, val name: String, val desc: String, val rawRes: Int)

object Sounds {
    const val DEFAULT_ID = "marimba"
    const val SYSTEM_ID = "system"
    const val CUSTOM_ID = "custom"

    val builtIn = listOf(
        Sound("marimba", "Маримба", "Бадьора мелодія", R.raw.alarm_marimba),
        Sound("sunrise", "Світанок", "М'які дзвіночки", R.raw.alarm_sunrise),
        Sound("harp", "Арфа", "Спокійне глісандо", R.raw.alarm_harp),
        Sound("birds", "Пташки", "Ранковий щебет", R.raw.alarm_birds),
        Sound("pulse", "Хвилі", "Низькі наростаючі тони", R.raw.alarm_pulse),
        Sound("classic", "Класичний", "Електронний сигнал", R.raw.alarm_classic),
        Sound("bells", "Дзвоники", "Механічний будильник, найгучніший", R.raw.alarm_bells),
    )

    private fun rawUri(context: Context, res: Int) = Uri.parse("android.resource://${context.packageName}/$res")

    /** Обраний звук, а за ним запасні: стандартний вбудований і системні мелодії. */
    fun candidates(context: Context, prefs: Prefs): List<Uri> {
        val chosen = uriFor(context, prefs.soundId, prefs)
        val fallback = rawUri(context, builtIn.first { it.id == DEFAULT_ID }.rawRes)
        val system = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
        return (listOfNotNull(chosen) + fallback + system).distinct()
    }

    fun displayName(prefs: Prefs): String = when (prefs.soundId) {
        SYSTEM_ID -> "Мелодія: ${prefs.systemSoundName ?: "системна"}"
        CUSTOM_ID -> "Свій: ${prefs.customSoundName ?: "файл"}"
        else -> builtIn.firstOrNull { it.id == prefs.soundId }?.name ?: builtIn.first().name
    }

    fun uriFor(context: Context, id: String, prefs: Prefs): Uri? = when (id) {
        SYSTEM_ID -> prefs.systemSoundUri?.let(Uri::parse)
        CUSTOM_ID -> prefs.customSoundUri?.let(Uri::parse)
        else -> builtIn.firstOrNull { it.id == id }?.let { rawUri(context, it.rawRes) }
    }
}

/** Коротке прослуховування звуку в налаштуваннях. */
class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null
    var playingId: String? = null
        private set

    fun play(id: String, uri: Uri, onStop: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.setOnCompletionListener {
                stop()
                onStop()
            }
            mp.prepare()
            mp.start()
            player = mp
            playingId = id
        } catch (_: Exception) {
            mp.release()
            onStop()
        }
    }

    fun stop() {
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        player = null
        playingId = null
    }
}
