package es.papa.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Tile(val pkg: String, val label: String)

data class Favorite(val name: String, val phone: String, val contactId: Long)

data class Config(
    val tiles: List<Tile>,
    val favorites: List<Favorite>,
    val pin: String,
    val guardEnabled: Boolean,
    val blockShade: Boolean,
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _config = MutableStateFlow(
        Config(emptyList(), emptyList(), DEFAULT_PIN, guardEnabled = true, blockShade = true)
    )
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Admin unlocked until this uptime (ms). In memory only: reboot = locked. */
    @Volatile var adminUntil: Long = 0L
    val isAdmin: Boolean get() = System.currentTimeMillis() < adminUntil

    fun unlockAdmin(minutes: Int = 10) {
        adminUntil = System.currentTimeMillis() + minutes * 60_000L
    }

    fun lockAdmin() { adminUntil = 0L }

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("papa", Context.MODE_PRIVATE)
        _config.value = Config(
            tiles = migrate(sp.getString(K_TILES, null)?.let(::parseTiles) ?: DEFAULT_TILES),
            favorites = sp.getString(K_FAVS, null)?.let(::parseFavs) ?: emptyList(),
            pin = sp.getString(K_PIN, DEFAULT_PIN)!!,
            guardEnabled = sp.getBoolean(K_GUARD, true),
            blockShade = sp.getBoolean(K_SHADE, true),
        )
    }

    fun update(block: (Config) -> Config) {
        val c = block(_config.value)
        _config.value = c
        sp.edit()
            .putString(K_TILES, JSONArray(c.tiles.map { JSONObject().put("p", it.pkg).put("l", it.label) }).toString())
            .putString(K_FAVS, JSONArray(c.favorites.map {
                JSONObject().put("n", it.name).put("t", it.phone).put("i", it.contactId)
            }).toString())
            .putString(K_PIN, c.pin)
            .putBoolean(K_GUARD, c.guardEnabled)
            .putBoolean(K_SHADE, c.blockShade)
            .apply()
    }

    private fun parseTiles(s: String): List<Tile> = runCatching {
        val a = JSONArray(s)
        List(a.length()) { a.getJSONObject(it).let { o -> Tile(o.getString("p"), o.getString("l")) } }
    }.getOrDefault(DEFAULT_TILES)

    private fun parseFavs(s: String): List<Favorite> = runCatching {
        val a = JSONArray(s)
        List(a.length()) {
            a.getJSONObject(it).let { o -> Favorite(o.getString("n"), o.getString("t"), o.optLong("i")) }
        }
    }.getOrDefault(emptyList())

    const val DEFAULT_PIN = "1234"
    private const val K_TILES = "tiles"
    private const val K_FAVS = "favs"
    private const val K_PIN = "pin"
    private const val K_GUARD = "guard"
    private const val K_SHADE = "shade"

    /** Special pseudo-packages resolved at launch time. */
    const val DIALER = "@dialer"
    const val CAMERA = "@camera"
    /** Built-in screens of this launcher. */
    const val KEYPAD = "@keypad"
    const val CONTACTS = "@contacts"
    val INTERNAL = setOf(KEYPAD, CONTACTS)

    /** Old tiles replaced by the built-in simple screens. */
    private fun migrate(t: List<Tile>) = t.map {
        when (it.pkg) {
            DIALER -> Tile(KEYPAD, it.label)
            "com.google.android.contacts" -> Tile(CONTACTS, it.label)
            else -> it
        }
    }.distinctBy { it.pkg }

    val DEFAULT_TILES = listOf(
        Tile(KEYPAD, "Teléfono"),
        Tile("com.whatsapp", "WhatsApp"),
        Tile(CONTACTS, "Contactos"),
        Tile(CAMERA, "Cámara"),
        Tile("com.google.android.apps.photos", "Fotos"),
        Tile("coches.net", "Coches"),
    )
}

/** Live notification counts per package, fed by [NotifService]. */
object Badges {
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
}
