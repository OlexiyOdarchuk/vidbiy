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
    private var bedtimeIssues by mutableStateOf(emptyList<String>())
    private var armRequest by mutableStateOf(false)
    private var screenRequest by mutableStateOf<String?>(null)

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
            update = ::update,
            checkUpdate = { Updater.check(this, force = true) },
            bedtimeIssues = { BedtimeCheck.issues(this) },
        )
        handleLaunch(intent)

        setContent {
            VidbiyTheme {
                VidbiyApp(
                    prefs, settings, permissions, actions, bedtimeIssues,
                    armRequest = armRequest,
                    onArmRequestSeen = { armRequest = false },
                    screenRequest = screenRequest,
                    onScreenRequestSeen = { screenRequest = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) settings.reloadSchedule()
        permissions = readPermissions()
        bedtimeIssues = BedtimeCheck.issues(this)
        Updater.check(this)
        Widgets.sync(this)
        Widgets.refreshIfStale(this)
    }

    override fun onStop() {
        super.onStop()
        // Місце могли змінити — віджети мають показати тривогу вже для нього.
        Widgets.refreshIfStale(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /**
     * Віджет відкрив програму на певному екрані, або плитка чи віджет просять увімкнути очікування:
     * не змогли з фону чи будильник на час уже ввімкнено — тоді спершу питаємо, чи справді будити
     * одразу після відбою.
     */
    private fun handleLaunch(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SCREEN)?.let {
            intent.removeExtra(EXTRA_SCREEN)
            screenRequest = it
        }
        if (intent?.getBooleanExtra(EXTRA_ARM, false) != true) return
        intent.removeExtra(EXTRA_ARM)
        if (WatchRepo.state.value.phase != Phase.IDLE) return
        if (AlarmScheduler.next(Prefs(this)) != null) armRequest = true else WatchService.arm(this)
    }

    companion object {
        const val EXTRA_ARM = "arm"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SCHEDULE = "schedule"
        const val SCREEN_STATS = "stats"
    }

    /** Кнопка «Оновити»: без дозволу на встановлення спершу ведемо в системні налаштування. */
    private fun update() {
        if (Updater.canInstall(this)) {
            Updater.install(this)
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
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
