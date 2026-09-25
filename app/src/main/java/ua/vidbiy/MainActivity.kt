package ua.vidbiy

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Notifications.createChannels(this)
        RegionTreeRepo.init(this)
        val prefs = Prefs(this)

        val actions = Actions(
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            openFullScreenSettings = ::openFullScreenSettings,
            openBatterySettings = ::openBatterySettings,
            arm = { WatchService.arm(this) },
            stop = { WatchService.send(this, WatchService.ACTION_STOP) },
            test = { WatchService.test(this) },
        )

        setContent {
            VidbiyTheme {
                VidbiyApp(prefs, permissions, actions)
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
}
