package ua.vidbiy

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** Перевірка перед сном: що може завадити будильнику спрацювати вночі. */
object BedtimeCheck {
    private const val LOW_BATTERY = 20

    fun issues(context: Context): List<String> = buildList {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..LOW_BATTERY && !battery.isCharging) add("Заряд $level %. Поставте телефон на зарядку")

        // Звук будильника програма гучнішає сама, але повна тиша «Не турбувати» глушить і будильники.
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            add("Увімкнено повну тишу «Не турбувати» — будильник може не прозвучати")
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            add("Немає інтернету — будильник не дізнається про відбій")
        }
    }
}
