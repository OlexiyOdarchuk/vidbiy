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

    /** Будильники на час. Раніше був один — його переносимо в список. */
    var alarms: List<Alarm>
        get() = AlarmsJson.alarms(sp.getString("alarms", null)) ?: listOf(
            Alarm(
                id = 1,
                enabled = sp.getBoolean("schedule_enabled", false),
                minutes = sp.getInt("schedule_minutes", 7 * 60 + 30),
                days = sp.getInt("schedule_days", AlarmScheduler.WEEKDAYS),
            )
        )
        set(value) { sp.edit().putString("alarms", AlarmsJson.write(value)).commit() }

    var nightRules: List<NightRule>
        get() = AlarmsJson.rules(sp.getString("night_rules", null)) ?: emptyList()
        set(value) = sp.edit().putString("night_rules", AlarmsJson.writeRules(value)).apply()

    /** Скільки хвилин відбій має втриматися, перш ніж будити; 0 — будити одразу. */
    var stableClearMinutes: Int
        get() = sp.getInt("stable_clear", 0)
        set(value) = sp.edit().putInt("stable_clear", value).apply()

    var bedtimeCheck: Boolean
        get() = sp.getBoolean("bedtime_check", true)
        set(value) = sp.edit().putBoolean("bedtime_check", value).apply()

    var alertStartNotice: Boolean
        get() = sp.getBoolean("alert_start_notice", false)
        set(value) = sp.edit().putBoolean("alert_start_notice", value).apply()

    /** За скільки хвилин до будильника на час починається світанок; 0 — вимкнено. */
    var sunriseMinutes: Int
        get() = sp.getInt("sunrise", 0)
        set(value) = sp.edit().putInt("sunrise", value).apply()

    var watchVibrate: Boolean
        get() = sp.getBoolean("watch_vibrate", false)
        set(value) = sp.edit().putBoolean("watch_vibrate", value).apply()

    // Що саме поставлено в системний будильник: час і які будильники (або перенесений правилом).
    var pendingAt: Long
        get() = sp.getLong("pending_at", 0L)
        set(value) { sp.edit().putLong("pending_at", value).commit() }

    var pendingIds: List<Int>
        get() = sp.getString("pending_ids", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        set(value) { sp.edit().putString("pending_ids", value.joinToString(",")).commit() }

    var pendingShifted: Boolean
        get() = sp.getBoolean("pending_shifted", false)
        set(value) { sp.edit().putBoolean("pending_shifted", value).commit() }

    /** Будильник, перенесений правилом нічної тривоги: коли, який і о котрій мав бути спершу. */
    var shiftAt: Long
        get() = sp.getLong("shift_at", 0L)
        set(value) { sp.edit().putLong("shift_at", value).commit() }

    var shiftAlarmId: Int
        get() = sp.getInt("shift_alarm", 0)
        set(value) { sp.edit().putInt("shift_alarm", value).commit() }

    /** Місце, за яким стежить поточне очікування, якщо воно не основне (будильник на час з іншим місцем). */
    var runPlace: Region?
        get() = sp.getString("run_place", null)?.let {
            try {
                AlarmsJson.region(org.json.JSONObject(it))
            } catch (_: org.json.JSONException) {
                null
            }
        }
        set(value) { sp.edit().putString("run_place", value?.let { AlarmsJson.region(it).toString() }).commit() }

    /** Поточне очікування запущене будильником за розкладом (а не дотиком до місяця). */
    var scheduleRun: Boolean
        get() = sp.getBoolean("schedule_run", false)
        set(value) { sp.edit().putBoolean("schedule_run", value).commit() }

    var scheduleLabel: String
        get() = sp.getString("schedule_label", "") ?: ""
        set(value) { sp.edit().putString("schedule_label", value).commit() }

    /** Яку версію туру вже бачили; менша за [TOUR_VERSION] — показати тур ще раз. */
    var tourVersion: Int
        get() = sp.getInt("tour_version", if (onboarded) 1 else 0)
        set(value) = sp.edit().putInt("tour_version", value).apply()

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

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(value) = sp.edit().putBoolean("auto_update", value).apply()

    var updateCheckedAt: Long
        get() = sp.getLong("update_checked_at", 0L)
        set(value) = sp.edit().putLong("update_checked_at", value).apply()

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
