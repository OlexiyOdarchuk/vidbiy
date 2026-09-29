package ua.vidbiy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val version: String?, val message: String) : UpdateState
}

/** Оновлення з релізів на GitHub: знаходить нову версію, завантажує APK і встановлює поверх поточної. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/OlexiyOdarchuk/vidbiy/releases/latest"
    private const val APK_NAME = "Vidbiy.apk"
    private const val ACTION_INSTALLED = "ua.vidbiy.UPDATE_INSTALLED"
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

    private data class Release(val version: String, val apkUrl: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var release: Release? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /**
     * Перевіряє нову версію (не частіше ніж раз на кілька годин, якщо не [force]).
     * Коли увімкнено автооновлення й дозволено встановлення, одразу завантажує й встановлює її.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }
        val known = release
        if (!force && System.currentTimeMillis() - prefs.updateCheckedAt < CHECK_EVERY_MS) {
            // Нову версію вже знайдено раніше: можливо, щойно дали дозвіл на встановлення.
            if (known != null && _state.value is UpdateState.Available) maybeAutoInstall(app, known)
            return
        }
        _state.value = UpdateState.Checking
        scope.launch {
            val found = try {
                fetchLatest()
            } catch (_: Exception) {
                _state.value = if (force) UpdateState.Failed(null, "Не вдалося перевірити оновлення") else UpdateState.Idle
                return@launch
            }
            prefs.updateCheckedAt = System.currentTimeMillis()
            if (found == null || !isNewer(found.version, currentVersion)) {
                release = null
                _state.value = UpdateState.UpToDate
                return@launch
            }
            release = found
            _state.value = UpdateState.Available(found.version)
            maybeAutoInstall(app, found)
        }
    }

    /** Завантажити й встановити знайдену версію (кнопка «Оновити»). */
    fun install(context: Context) {
        val found = release ?: return
        val app = context.applicationContext
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }
        _state.value = UpdateState.Downloading(found.version, 0f)
        scope.launch {
            try {
                val apk = download(app, found)
                _state.value = UpdateState.Installing(found.version)
                commit(app, apk)
            } catch (_: Exception) {
                _state.value = UpdateState.Failed(found.version, "Не вдалося завантажити оновлення")
            }
        }
    }

    private fun maybeAutoInstall(context: Context, found: Release) {
        // Встановлення перезапускає програму, тож не чіпаємо її, поки будильник стежить чи дзвонить.
        val prefs = Prefs(context)
        if (prefs.autoUpdate && canInstall(context) && WatchRepo.state.value.phase == Phase.IDLE && !prefs.armed) {
            release = found
            install(context)
        }
    }

    private fun fetchLatest(): Release? {
        val json = JSONObject(httpGet(LATEST).use { it.inputStream.bufferedReader().readText() })
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk") }
        val apk = apks.firstOrNull { it.getString("name") == APK_NAME } ?: apks.firstOrNull() ?: return null
        return Release(version, apk.getString("browser_download_url"))
    }

    private fun download(context: Context, found: Release): File {
        val file = File(context.cacheDir, "update.apk")
        httpGet(found.apkUrl).use { conn ->
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(found.version, done.toFloat() / total)
                    }
                }
            }
        }
        // Файл має бути саме цією програмою, інакше система все одно відмовить, але пізніше й незрозуміліше.
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info?.packageName != context.packageName) throw IOException("not our package")
        return file
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // З Android 12 програма може оновити саму себе без окремого підтвердження.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALLED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALLED) return
        val version = release?.version
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    _state.value = UpdateState.Failed(version, "Не вдалося встановити оновлення")
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    _state.value = UpdateState.Failed(version, "Не вдалося встановити оновлення")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // система перезапускає програму вже новою версією
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = version?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(version, "Не вдалося встановити оновлення")
        }
    }

    private fun httpGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Vidbiy/$currentVersion")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode}")
        }
        return conn
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    /** 1.10 новіша за 1.9, а 1.3 — за 1.2.2. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallResult(context, intent)
}
