package dev.touchport

import android.content.Context

enum class LinkMode { AUTO, ADB, MANUAL }

enum class GyroMode { OFF, ALWAYS, ADS, FIRE_ADS }

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var linkMode = enumOr(p.getString("linkMode", null), LinkMode.AUTO)
    var manualIp: String = p.getString("manualIp", "") ?: ""
    var sens = p.getFloat("sens", 1f)               // touch aim
    var invertY = p.getBoolean("invertY", false)
    var gyroMode = enumOr(p.getString("gyroMode", null), GyroMode.ADS)
    var gyroSens = p.getFloat("gyroSens", 1f)
    var gyroRoll = p.getBoolean("gyroRoll", false)  // steer left/right with roll instead of yaw
    var gyroInvertX = p.getBoolean("gyroInvertX", false)
    var gyroInvertY = p.getBoolean("gyroInvertY", false)
    var joyZone = p.getFloat("joyZone", 0.45f)      // floating joystick area, fraction of width
    var haptics = p.getBoolean("haptics", true)

    fun save() {
        p.edit()
            .putString("linkMode", linkMode.name).putString("manualIp", manualIp)
            .putFloat("sens", sens).putBoolean("invertY", invertY)
            .putString("gyroMode", gyroMode.name).putFloat("gyroSens", gyroSens)
            .putBoolean("gyroRoll", gyroRoll).putBoolean("gyroInvertX", gyroInvertX)
            .putBoolean("gyroInvertY", gyroInvertY)
            .putFloat("joyZone", joyZone).putBoolean("haptics", haptics)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
