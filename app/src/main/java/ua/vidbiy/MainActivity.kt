package ua.vidbiy

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
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

    private lateinit var settings: SettingsState

    private val systemSoundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.let {
                if (Build.VERSION.SDK_INT >= 33) {
                    it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
            } ?: return@registerForActivityResult
            settings.setSystemSound(uri.toString(), RingtoneManager.getRingtone(this, uri)?.getTitle(this))
        }

    private val customSoundPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Без постійного дозволу файл перестане відкриватися після перезапуску телефона.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            settings.setCustomSound(uri.toString(), name)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Notifications.createChannels(this)
        RegionTreeRepo.init(this)
        val prefs = Prefs(this)
        settings = SettingsState(prefs) { AlarmScheduler.schedule(this) }
        // Будильник за розкладом міг загубитися (примусова зупинка програми) — ставимо заново.
        AlarmScheduler.schedule(this)

        val actions = Actions(
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            openFullScreenSettings = ::openFullScreenSettings,
            openBatterySettings = ::openBatterySettings,
            arm = { WatchService.arm(this) },
            stop = { WatchService.send(this, WatchService.ACTION_STOP) },
            test = { WatchService.test(this) },
            pickSystemSound = ::pickSystemSound,
            pickCustomSound = { customSoundPicker.launch(arrayOf("audio/*")) },
            openExactAlarmSettings = ::openExactAlarmSettings,
        )

        setContent {
            VidbiyTheme {
                VidbiyApp(prefs, settings, permissions, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) settings.reloadSchedule()
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

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun pickSystemSound() {
        val current = settings.prefs.systemSoundUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        systemSoundPicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодія будильника")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        )
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }
}
