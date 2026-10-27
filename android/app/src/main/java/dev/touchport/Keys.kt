package dev.touchport

/** A key the laptop receiver can press. [bit] must match KEYMAP in receiver/touchport-receiver.c. */
class Key(val id: String, val label: String, val bit: Int)

object Keys {
    // Special actions handled on the phone, not sent as a held key.
    const val WHEEL_UP = -1
    const val WHEEL_DOWN = -2
    const val GYRO_TOGGLE = -3

    // Bits the joystick uses.
    const val W = 0
    const val A = 1
    const val S = 2
    const val D = 3
    const val SHIFT = 5
    const val UP = 51
    const val DOWN = 52
    const val LEFT = 53
    const val RIGHT = 54
    const val LMB = 32
    const val RMB = 33

    val all = listOf(
        Key("LMB", "Mouse left", LMB),
        Key("RMB", "Mouse right", RMB),
        Key("MMB", "Mouse middle", 34),
        Key("MB4", "Mouse 4 (back)", 35),
        Key("MB5", "Mouse 5 (forward)", 36),
        Key("WHEEL_UP", "Wheel up", WHEEL_UP),
        Key("WHEEL_DOWN", "Wheel down", WHEEL_DOWN),
        Key("GYRO", "Gyro on / off", GYRO_TOGGLE),
        Key("SPACE", "Space", 4),
        Key("LSHIFT", "Left Shift", SHIFT),
        Key("LCTRL", "Left Ctrl", 6),
        Key("LALT", "Left Alt", 31),
        Key("TAB", "Tab", 18),
        Key("ESC", "Esc", 19),
        Key("ENTER", "Enter", 30),
        Key("1", "1", 20), Key("2", "2", 21), Key("3", "3", 22), Key("4", "4", 23), Key("5", "5", 24),
        Key("6", "6", 43), Key("7", "7", 44), Key("8", "8", 45), Key("9", "9", 46), Key("0", "0", 47),
        Key("W", "W", W), Key("A", "A", A), Key("S", "S", S), Key("D", "D", D),
        Key("B", "B", 16), Key("C", "C", 7), Key("E", "E", 8), Key("F", "F", 9),
        Key("G", "G", 13), Key("H", "H", 14), Key("I", "I", 40), Key("J", "J", 25),
        Key("K", "K", 26), Key("L", "L", 42), Key("M", "M", 28), Key("N", "N", 37),
        Key("O", "O", 41), Key("P", "P", 27), Key("Q", "Q", 10), Key("R", "R", 11),
        Key("T", "T", 15), Key("U", "U", 39), Key("V", "V", 12), Key("X", "X", 29),
        Key("Y", "Y", 38), Key("Z", "Z", 17),
        Key("UP", "Arrow up", UP), Key("DOWN", "Arrow down", DOWN),
        Key("LEFT", "Arrow left", LEFT), Key("RIGHT", "Arrow right", RIGHT),
        Key("CAPS", "Caps Lock", 48), Key("BKSP", "Backspace", 49), Key("GRAVE", "` (console)", 50),
        Key("F1", "F1", 55), Key("F2", "F2", 56), Key("F3", "F3", 57), Key("F4", "F4", 58),
        Key("F5", "F5", 59), Key("F6", "F6", 60), Key("F7", "F7", 61), Key("F8", "F8", 62), Key("F9", "F9", 63),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Key = byId[id] ?: byId.getValue("E")
}
