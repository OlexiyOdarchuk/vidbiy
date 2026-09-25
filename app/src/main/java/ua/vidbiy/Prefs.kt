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
