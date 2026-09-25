package ua.vidbiy

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

data class Permissions(
    val notifications: Boolean = true,
    val fullScreen: Boolean = true,
    val battery: Boolean = true,
)

class MainActivity : ComponentActivity() {
    private var permissions by mutableStateOf(Permissions())

    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissions = readPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        val prefs = Prefs(this)

        setContent {
            VidbiyTheme {
                MainScreen(
                    prefs = prefs,
                    permissions = permissions,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenFullScreenSettings = ::openFullScreenSettings,
                    onOpenBatterySettings = ::openBatterySettings,
                    onPickTime = ::pickTime,
                    onArm = { WatchService.arm(this) },
                    onStop = { WatchService.send(this, WatchService.ACTION_STOP) },
                    onTest = { WatchService.test(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions = readPermissions()
    }

    private fun readPermissions(): Permissions {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return Permissions(
            notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            battery = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun pickTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        val start = if (initialMinutes >= 0) initialMinutes else 14 * 60
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, start / 60, start % 60, true).show()
    }
}
