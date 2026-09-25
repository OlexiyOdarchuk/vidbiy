package ua.vidbiy

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Place(val id: String, val name: String, val parentId: String?, val childIds: List<String>)

class RegionTree(private val places: Map<String, Place>, val rootIds: List<String>) {
    operator fun get(id: String): Place? = places[id]

    val all: Collection<Place> get() = places.values

    fun ancestors(id: String): List<String> =
        generateSequence(places[id]?.parentId) { places[it]?.parentId }.toList()

    fun descendants(id: String): Set<String> {
        val result = HashSet<String>()
        fun walk(pid: String) {
            places[pid]?.childIds?.forEach {
                result += it
                walk(it)
            }
        }
        walk(id)
        return result
    }

    fun path(id: String): String = ancestors(id).mapNotNull { places[it]?.name }.joinToString(", ")
}

object RegionTreeRepo {
    private const val URL_REGIONS = "https://siren.pp.ua/api/v3/regions"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TEST_REGION_ID = "0"

    @Volatile
    private var tree: RegionTree? = null
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "regions.json")
    }

    fun cached(): RegionTree? = tree

    @Synchronized
    fun get(): RegionTree? {
        val f = file
        val fresh = f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
        if (tree != null && fresh) return tree
        if (tree == null && f != null && f.exists()) tree = parseOrNull(f.readText())
        if (!fresh) download()?.let { body ->
            parseOrNull(body)?.let {
                tree = it
                f?.writeText(body)
            }
        }
        return tree
    }

    private fun download(): String? =
        try {
            val conn = URL(URL_REGIONS).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Vidbiy/${BuildConfig.VERSION_NAME} (Android)")
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }

    private fun parseOrNull(body: String): RegionTree? =
        try {
            val places = HashMap<String, Place>()
            fun walk(node: JSONObject, parentId: String?): String {
                val id = node.getString("regionId")
                val children = node.optJSONArray("regionChildIds")
                val childIds = ArrayList<String>()
                if (children != null) {
                    for (i in 0 until children.length()) childIds += walk(children.getJSONObject(i), id)
                }
                places[id] = Place(id, node.getString("regionName"), parentId, childIds)
                return id
            }

            val states = JSONObject(body).getJSONArray("states")
            val roots = ArrayList<String>()
            for (i in 0 until states.length()) {
                val id = walk(states.getJSONObject(i), null)
                if (id != TEST_REGION_ID) roots += id
            }
            RegionTree(places, roots)
        } catch (_: JSONException) {
            null
        }
}
