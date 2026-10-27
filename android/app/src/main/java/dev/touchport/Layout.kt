package dev.touchport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Kind { BUTTON, JOYSTICK }

/** One control on the HUD. Position and size are fractions of the screen, so a layout fits any phone. */
class Control(
    var kind: Kind,
    var label: String,
    var keyId: String,
    var x: Float,                   // centre, fraction of view width
    var y: Float,                   // centre, fraction of view height
    var size: Float,                // diameter, fraction of view height
    var color: Int,
    var alpha: Float = 0.6f,
    var toggle: Boolean = false,    // tap on, tap off (instead of hold)
    var aimDrag: Boolean = false,   // dragging the thumb on this button also aims
    var floating: Boolean = true,   // joystick: appears where the left thumb lands
    var autoSprint: Boolean = true, // joystick: hold the sprint key when pushed fully forward
    var arrows: Boolean = false,    // joystick: arrow keys instead of WASD
    var sprintKeyId: String = "LSHIFT",
) {
    // Runtime state, not saved.
    var pointer = -1
    var on = false

    val key: Key get() = Keys.byId(keyId)
    val sprintKey: Key get() = Keys.byId(sprintKeyId)

    fun copyFrom(o: Control) {
        kind = o.kind; label = o.label; keyId = o.keyId; x = o.x; y = o.y; size = o.size
        color = o.color; alpha = o.alpha; toggle = o.toggle; aimDrag = o.aimDrag
        floating = o.floating; autoSprint = o.autoSprint; arrows = o.arrows; sprintKeyId = o.sprintKeyId
    }

    fun copy() = Control(kind, label, keyId, x, y, size, color, alpha, toggle, aimDrag, floating, autoSprint, arrows, sprintKeyId)

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("label", label).put("key", keyId)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("size", size.toDouble())
        .put("color", color).put("alpha", alpha.toDouble())
        .put("toggle", toggle).put("aimDrag", aimDrag)
        .put("floating", floating).put("autoSprint", autoSprint)
        .put("arrows", arrows).put("sprintKey", sprintKeyId)

    companion object {
        fun fromJson(o: JSONObject) = Control(
            Kind.valueOf(o.getString("kind")), o.getString("label"), o.optString("key", "E"),
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("size").toFloat(),
            o.optInt("color", Colors.GREY), o.optDouble("alpha", 0.6).toFloat(),
            o.optBoolean("toggle"), o.optBoolean("aimDrag"),
            o.optBoolean("floating", true), o.optBoolean("autoSprint", true),
            o.optBoolean("arrows"), o.optString("sprintKey", "LSHIFT"),
        )
    }
}

/** Strong, different colours so each main button is easy to find with peripheral vision. */
object Colors {
    const val RED = 0xFFE53935.toInt()
    const val ORANGE = 0xFFFB8C00.toInt()
    const val YELLOW = 0xFFFDD835.toInt()
    const val LIME = 0xFFC0CA33.toInt()
    const val GREEN = 0xFF43A047.toInt()
    const val TEAL = 0xFF00897B.toInt()
    const val CYAN = 0xFF00ACC1.toInt()
    const val SKY = 0xFF4FC3F7.toInt()
    const val BLUE = 0xFF1E88E5.toInt()
    const val PURPLE = 0xFF8E24AA.toInt()
    const val PINK = 0xFFD81B60.toInt()
    const val WHITE = 0xFFECEFF1.toInt()
    const val GREY = 0xFF78909C.toInt()

    val palette = intArrayOf(RED, ORANGE, YELLOW, LIME, GREEN, TEAL, CYAN, SKY, BLUE, PURPLE, PINK, WHITE, GREY)
}

/** Ready-made layouts. Users start from one of these and change it. */
object Presets {
    val all: List<Pair<String, () -> MutableList<Control>>> = listOf(
        "Generic FPS" to ::genericFps,
        "The Finals" to ::theFinals,
        "Blank (joystick only)" to ::blank,
    )

    /** WASD move, Space jump, Shift sprint, Ctrl crouch, mouse fire / aim, the usual shooter keys. */
    fun genericFps(): MutableList<Control> = mutableListOf(
        Control(Kind.JOYSTICK, "", "W", 0.16f, 0.68f, 0.38f, Colors.WHITE, 0.5f),

        Control(Kind.BUTTON, "FIRE", "LMB", 0.87f, 0.58f, 0.22f, Colors.RED, 0.7f, aimDrag = true),
        Control(Kind.BUTTON, "FIRE", "LMB", 0.30f, 0.34f, 0.15f, Colors.RED, 0.6f, aimDrag = true),
        Control(Kind.BUTTON, "ADS", "RMB", 0.95f, 0.36f, 0.14f, Colors.ORANGE, 0.7f, toggle = true, aimDrag = true),
        Control(Kind.BUTTON, "JUMP", "SPACE", 0.94f, 0.82f, 0.17f, Colors.GREEN, 0.7f),
        Control(Kind.BUTTON, "CROUCH", "LCTRL", 0.81f, 0.86f, 0.15f, Colors.BLUE, 0.7f),
        Control(Kind.BUTTON, "RELOAD", "R", 0.74f, 0.70f, 0.12f, Colors.YELLOW, 0.7f),
        Control(Kind.BUTTON, "ABILITY", "Q", 0.67f, 0.87f, 0.14f, Colors.PURPLE, 0.7f),
        Control(Kind.BUTTON, "MELEE", "V", 0.75f, 0.40f, 0.12f, Colors.PINK, 0.65f, aimDrag = true),
        Control(Kind.BUTTON, "USE", "E", 0.58f, 0.62f, 0.13f, Colors.WHITE, 0.65f),
        Control(Kind.BUTTON, "NADE", "G", 0.58f, 0.44f, 0.11f, Colors.TEAL, 0.6f),
        Control(Kind.BUTTON, "SPRINT", "LSHIFT", 0.06f, 0.42f, 0.11f, Colors.LIME, 0.55f),

        Control(Kind.BUTTON, "1", "1", 0.50f, 0.10f, 0.10f, Colors.CYAN, 0.6f),
        Control(Kind.BUTTON, "2", "2", 0.60f, 0.10f, 0.10f, Colors.CYAN, 0.6f),
        Control(Kind.BUTTON, "3", "3", 0.70f, 0.10f, 0.10f, Colors.CYAN, 0.6f),
        Control(Kind.BUTTON, "4", "4", 0.80f, 0.10f, 0.10f, Colors.CYAN, 0.6f),
        Control(Kind.BUTTON, "F", "F", 0.40f, 0.10f, 0.10f, Colors.SKY, 0.6f),

        Control(Kind.BUTTON, "TAB", "TAB", 0.14f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "MAP", "M", 0.23f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "ESC", "ESC", 0.94f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "GYRO", "GYRO", 0.45f, 0.87f, 0.10f, Colors.GREY, 0.5f),
    )

    /**
     * The Finals with its default PC key bindings:
     * WASD move, Space jump, Shift sprint, Ctrl crouch/slide, LMB fire, RMB aim, R reload,
     * Q specialization, V melee, E interact, F pick up, 1 weapon, 2-4 gadgets, MMB ping,
     * Tab scoreboard, B communication wheel, H emote, Esc menu.
     */
    fun theFinals(): MutableList<Control> = mutableListOf(
        Control(Kind.JOYSTICK, "", "W", 0.16f, 0.68f, 0.38f, Colors.WHITE, 0.5f),

        Control(Kind.BUTTON, "FIRE", "LMB", 0.87f, 0.58f, 0.22f, Colors.RED, 0.7f, aimDrag = true),
        Control(Kind.BUTTON, "FIRE", "LMB", 0.30f, 0.34f, 0.15f, Colors.RED, 0.6f, aimDrag = true),
        Control(Kind.BUTTON, "ADS", "RMB", 0.95f, 0.36f, 0.14f, Colors.ORANGE, 0.7f, toggle = true, aimDrag = true),
        Control(Kind.BUTTON, "JUMP", "SPACE", 0.94f, 0.82f, 0.17f, Colors.GREEN, 0.7f),
        Control(Kind.BUTTON, "SLIDE", "LCTRL", 0.81f, 0.86f, 0.15f, Colors.BLUE, 0.7f),
        Control(Kind.BUTTON, "RELOAD", "R", 0.74f, 0.70f, 0.12f, Colors.YELLOW, 0.7f),
        Control(Kind.BUTTON, "SPEC", "Q", 0.67f, 0.87f, 0.14f, Colors.PURPLE, 0.7f),
        Control(Kind.BUTTON, "MELEE", "V", 0.75f, 0.40f, 0.12f, Colors.PINK, 0.65f, aimDrag = true),
        Control(Kind.BUTTON, "USE", "E", 0.58f, 0.62f, 0.13f, Colors.WHITE, 0.65f),
        Control(Kind.BUTTON, "PICK", "F", 0.58f, 0.44f, 0.11f, Colors.TEAL, 0.6f),
        Control(Kind.BUTTON, "SPRINT", "LSHIFT", 0.06f, 0.42f, 0.11f, Colors.LIME, 0.55f),

        Control(Kind.BUTTON, "GUN", "1", 0.50f, 0.10f, 0.10f, Colors.GREY, 0.55f),
        Control(Kind.BUTTON, "G1", "2", 0.60f, 0.10f, 0.11f, Colors.CYAN, 0.65f),
        Control(Kind.BUTTON, "G2", "3", 0.70f, 0.10f, 0.11f, Colors.CYAN, 0.65f),
        Control(Kind.BUTTON, "G3", "4", 0.80f, 0.10f, 0.11f, Colors.CYAN, 0.65f),
        Control(Kind.BUTTON, "PING", "MMB", 0.40f, 0.10f, 0.10f, Colors.SKY, 0.6f),

        Control(Kind.BUTTON, "TAB", "TAB", 0.14f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "COMMS", "B", 0.23f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "EMOTE", "H", 0.32f, 0.10f, 0.08f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "ESC", "ESC", 0.94f, 0.10f, 0.09f, Colors.GREY, 0.45f),
        Control(Kind.BUTTON, "GYRO", "GYRO", 0.45f, 0.87f, 0.10f, Colors.GREY, 0.5f),
    )

    fun blank(): MutableList<Control> = mutableListOf(
        Control(Kind.JOYSTICK, "", "W", 0.16f, 0.68f, 0.38f, Colors.WHITE, 0.5f),
    )
}

class Profile(var name: String, val controls: MutableList<Control>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("controls", JSONArray().apply {
        controls.forEach { put(it.toJson()) }
    })

    companion object {
        fun fromJson(o: JSONObject) = Profile(o.getString("name"), parseControls(o.getJSONArray("controls")))

        fun parseControls(arr: JSONArray) = MutableList(arr.length()) { Control.fromJson(arr.getJSONObject(it)) }
    }
}

/**
 * All profiles (one layout per game) and which one is active.
 *
 * Saved in SharedPreferences. Every save also writes a backup to
 * Android/data/<app id>/files/profiles-backup.json. A file named import.json in the same folder
 * is imported on start and then deleted (profiles with the same name are replaced).
 */
class ProfileStore(private val ctx: Context) {
    val profiles = mutableListOf<Profile>()
    var active = 0
        private set

    val current: Profile get() = profiles[active]

    private val prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    init {
        val raw = prefs.getString(KEY, null)
        if (raw == null || runCatching { read(JSONObject(raw)) }.isFailure || profiles.isEmpty()) {
            profiles.clear()
            // The single-layout version (TheFinalCNTRL 0.1) kept its layout here. Keep it as it is.
            // The old key is left in place, so nothing is lost if anything goes wrong.
            val old = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE).getString("layout_v1", null)
            val oldControls = old?.let { runCatching { Profile.parseControls(JSONArray(it)) }.getOrNull() }
            if (oldControls != null) {
                profiles += Profile("The Finals", oldControls)
                profiles += Profile("Generic FPS", Presets.genericFps())
            } else {
                profiles += Profile("Generic FPS", Presets.genericFps())
                profiles += Profile("The Finals", Presets.theFinals())
            }
            active = 0
        }
        importFile()
        save()
    }

    fun select(i: Int) {
        if (i in profiles.indices) {
            active = i
            save()
        }
    }

    fun add(name: String, controls: MutableList<Control>): Int {
        profiles += Profile(uniqueName(name), controls)
        return profiles.size - 1
    }

    fun rename(i: Int, name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && profiles.none { it !== profiles[i] && it.name == n }) profiles[i].name = n
        save()
    }

    fun delete(i: Int) {
        if (profiles.size <= 1) return
        profiles.removeAt(i)
        if (active >= profiles.size || active == i) active = 0 else if (active > i) active--
        save()
    }

    fun save() {
        val json = toJson(profiles)
        prefs.edit().putString(KEY, json.toString()).apply()
        try {
            ctx.getExternalFilesDir(null)?.let { File(it, "profiles-backup.json").writeText(json.toString(2)) }
        } catch (_: Exception) {
        }
    }

    fun toJson(list: List<Profile>): JSONObject = JSONObject()
        .put("format", "touchport-profiles").put("version", 1)
        .put("active", if (active in profiles.indices) profiles[active].name else "")
        .put("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } })

    /** Adds the profiles in [text]. With [replace], a profile with the same name is overwritten. */
    fun importJson(text: String, replace: Boolean): Int {
        val o = JSONObject(text)
        val arr = o.getJSONArray("profiles")
        var n = 0
        for (i in 0 until arr.length()) {
            val p = Profile.fromJson(arr.getJSONObject(i))
            val same = profiles.indexOfFirst { it.name == p.name }
            if (replace && same >= 0) profiles[same] = p else add(p.name, p.controls)
            n++
        }
        if (replace) {
            val want = profiles.indexOfFirst { it.name == o.optString("active") }
            if (want >= 0) active = want
        }
        save()
        return n
    }

    private fun read(o: JSONObject) {
        val arr = o.getJSONArray("profiles")
        for (i in 0 until arr.length()) profiles += Profile.fromJson(arr.getJSONObject(i))
        active = profiles.indexOfFirst { it.name == o.optString("active") }.coerceAtLeast(0)
    }

    private fun importFile() {
        try {
            val f = File(ctx.getExternalFilesDir(null) ?: return, "import.json")
            if (!f.exists()) return
            importJson(f.readText(), replace = true)
            f.delete()
        } catch (_: Exception) {
        }
    }

    private fun uniqueName(base: String): String {
        val b = base.trim().ifEmpty { "Profile" }
        if (profiles.none { it.name == b }) return b
        var i = 2
        while (profiles.any { it.name == "$b ($i)" }) i++
        return "$b ($i)"
    }

    private companion object {
        const val KEY = "profiles_v1"
    }
}
