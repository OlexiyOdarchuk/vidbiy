package ua.vidbiy

import android.content.Context
import java.time.ZonedDateTime

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vidbiy", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value.trim()).apply()

    var region: Region
        get() {
            val placeId = sp.getString("place_id", null) ?: return Regions.byUid(sp.getInt("region", Regions.DEFAULT_UID))
            return Region(
                uid = null,
                name = sp.getString("place_name", "") ?: "",
                sirenId = placeId,
                ubillingName = null,
                detail = sp.getString("place_detail", null),
            )
        }
        set(value) {
            val edit = sp.edit()
            if (value.uid != null) {
                edit.putInt("region", value.uid).remove("place_id").remove("place_name").remove("place_detail")
            } else {
                edit.putString("place_id", value.sirenId).putString("place_name", value.name)
                    .putString("place_detail", value.detail)
            }
            edit.apply()
        }

    var soundId: String
        get() = sp.getString("sound", Sounds.DEFAULT_ID) ?: Sounds.DEFAULT_ID
        set(value) = sp.edit().putString("sound", value).apply()

    // Мелодія системи й свій файл зберігаються окремо, щоб вибір одного не стирав інший.
    var systemSoundUri: String?
        get() = sp.getString("system_sound_uri", null)
        set(value) = sp.edit().putString("system_sound_uri", value).apply()

    var systemSoundName: String?
        get() = sp.getString("system_sound_name", null)
        set(value) = sp.edit().putString("system_sound_name", value).apply()

    var customSoundUri: String?
        get() = sp.getString("custom_sound_uri", null)
        set(value) = sp.edit().putString("custom_sound_uri", value).apply()

    var customSoundName: String?
        get() = sp.getString("custom_sound_name", null)
        set(value) = sp.edit().putString("custom_sound_name", value).apply()

    // Будильник за розкладом: о заданій порі будить, а під час тривоги чекає відбою.
    var scheduleEnabled: Boolean
        get() = sp.getBoolean("schedule_enabled", false)
        set(value) = sp.edit().putBoolean("schedule_enabled", value).apply()

    var scheduleMinutes: Int
        get() = sp.getInt("schedule_minutes", 7 * 60 + 30)
        set(value) = sp.edit().putInt("schedule_minutes", value).apply()

    /** Дні тижня бітами: біт 0 — понеділок … біт 6 — неділя; 0 — один раз. */
    var scheduleDays: Int
        get() = sp.getInt("schedule_days", 0b0011111)
        set(value) = sp.edit().putInt("schedule_days", value).apply()

    /** Поточне очікування запущене будильником за розкладом (а не дотиком до місяця). */
    var scheduleRun: Boolean
        get() = sp.getBoolean("schedule_run", false)
        set(value) { sp.edit().putBoolean("schedule_run", value).commit() }

    var scheduleLabel: String
        get() = sp.getString("schedule_label", "") ?: ""
        set(value) { sp.edit().putString("schedule_label", value).commit() }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(value) = sp.edit().putBoolean("onboarded", value).apply()

    var cutoffMinutes: Int
        get() = sp.getInt("cutoff", -1)
        set(value) = sp.edit().putInt("cutoff", value).apply()

    var source: Source
        get() = Source.entries.firstOrNull { it.name == sp.getString("source", null) } ?: Source.SIREN
        set(value) = sp.edit().putString("source", value.name).apply()

    var alarmOnNoConnection: Boolean
        get() = sp.getBoolean("alarm_no_conn", true)
        set(value) = sp.edit().putBoolean("alarm_no_conn", value).apply()

    var armed: Boolean
        get() = sp.getBoolean("armed", false)
        set(value) {
            sp.edit().putBoolean("armed", value).commit()
        }

    var sawAlert: Boolean
        get() = sp.getBoolean("saw_alert", false)
        set(value) {
            sp.edit().putBoolean("saw_alert", value).commit()
        }

    var cutoffAt: Long
        get() = sp.getLong("cutoff_at", 0L)
        set(value) {
            sp.edit().putLong("cutoff_at", value).commit()
        }

    companion object {
        fun nextOccurrence(minutes: Int): Long {
            val now = ZonedDateTime.now()
            var t = now.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(now.zone)
            if (!t.isAfter(now)) t = t.plusDays(1)
            return t.toInstant().toEpochMilli()
        }

        fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
