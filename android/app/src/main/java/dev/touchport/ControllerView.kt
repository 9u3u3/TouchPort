package dev.touchport

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * The HUD. Draws the controls, turns multi-touch into keys and mouse motion, and has an edit mode
 * to move, resize, recolour and rebind controls.
 */
@SuppressLint("ViewConstructor")
class ControllerView(
    context: Context,
    private val link: Link,
    private val gyro: Gyro,
    private val settings: Settings,
    private val store: ProfileStore,
    private val onSettingsChanged: () -> Unit,
) : View(context) {

    private val controls: MutableList<Control> get() = store.current.controls
    var editing = false
        private set

    private val dp = resources.displayMetrics.density

    // Per pointer id: NONE, AIM, JOY, GEAR, or the index of the pressed button.
    private val role = IntArray(MAX_POINTERS) { NONE }
    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)

    private var joyPointer = -1
    private var joyCx = 0f
    private var joyCy = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var joyMask = 0L

    // Edit mode drag.
    private var dragIdx = -1
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragOrigX = 0f
    private var dragOrigY = 0f
    private var dragMoved = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * dp
        color = 0xB0FFFFFF.toInt()
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0x60FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    }
    private val tools = arrayOf("+ Add", "Settings", "Reset", "Done")
    private val toolRects = Array(tools.size) { RectF() }

    init {
        setBackgroundColor(Color.BLACK)
        // Deliver every touch sample at once instead of batching them per frame (Android 11+).
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(InputDevice.SOURCE_TOUCHSCREEN)
    }

    private val joystick get() = controls.firstOrNull { it.kind == Kind.JOYSTICK }
    private fun cx(c: Control) = c.x * width
    private fun cy(c: Control) = c.y * height
    private fun radius(c: Control) = c.size * height / 2f
    private val gearX get() = 26 * dp
    private val gearY get() = 26 * dp

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep the back gesture away from the joystick and the fire button.
            val w = right - left
            val h = bottom - top
            val band = (200 * dp).toInt()
            val edge = (40 * dp).toInt()
            val fireY = (h * 0.58f).toInt()
            systemGestureExclusionRects = listOf(
                Rect(0, h - band, edge, h),
                Rect(w - edge, fireY - band / 2, w, fireY + band / 2),
            )
        }
    }

    // ---- Play mode ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) {
            editTouch(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (id >= MAX_POINTERS) return
        lastX[id] = x
        lastY[id] = y
        if (hypot(x - gearX, y - gearY) < 22 * dp) {
            role[id] = GEAR
            return
        }
        val b = hitButton(x, y)
        if (b >= 0) {
            val c = controls[b]
            role[id] = b
            c.pointer = id
            when (c.key.bit) {
                Keys.WHEEL_UP -> link.addWheel(1)
                Keys.WHEEL_DOWN -> link.addWheel(-1)
                Keys.GYRO_TOGGLE -> gyro.enabled = !gyro.enabled
                else -> if (c.toggle) c.on = !c.on
            }
            keysChanged()
            if (settings.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        val j = joystick
        if (j != null && joyPointer < 0) {
            val inZone = if (j.floating) x < width * settings.joyZone
            else hypot(x - cx(j), y - cy(j)) < radius(j) * 1.4f
            if (inZone) {
                role[id] = JOY
                joyPointer = id
                if (j.floating) {
                    joyCx = x
                    joyCy = y
                } else {
                    joyCx = cx(j)
                    joyCy = cy(j)
                }
                stick(j, x, y)
                return
            }
        }
        role[id] = AIM
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id >= MAX_POINTERS) return
        val r = role[id]
        val dx = x - lastX[id]
        val dy = y - lastY[id]
        lastX[id] = x
        lastY[id] = y
        when {
            r == AIM -> aim(dx, dy)
            r == JOY -> joystick?.let { stick(it, x, y) }
            r >= 0 && r < controls.size && controls[r].aimDrag -> aim(dx, dy)
        }
    }

    private fun up(id: Int) {
        if (id >= MAX_POINTERS) return
        val r = role[id]
        role[id] = NONE
        when {
            r == JOY -> {
                joyPointer = -1
                joyMask = 0
                keysChanged()
            }
            r == GEAR -> showMenu()
            r >= 0 && r < controls.size -> {
                val c = controls[r]
                if (c.pointer == id) c.pointer = -1
                keysChanged()
            }
        }
    }

    fun releaseAll() {
        role.fill(NONE)
        joyPointer = -1
        joyMask = 0
        for (c in controls) {
            c.pointer = -1
            c.on = false
        }
        keysChanged()
    }

    private fun aim(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val k = settings.sens * COUNTS_PER_DP / dp
        link.addMouse(dx * k, if (settings.invertY) -dy * k else dy * k)
    }

    private fun stick(j: Control, x: Float, y: Float) {
        // The base stays locked where the thumb landed until the thumb lifts.
        val r = radius(j)
        val dx = x - joyCx
        val dy = y - joyCy
        val len = hypot(dx, dy)
        val clamp = if (len > r) r / len else 1f
        knobX = joyCx + dx * clamp
        knobY = joyCy + dy * clamp

        var m = 0L
        if (len / r > DEADZONE) {
            val ux = dx / len
            val uy = dy / len
            if (uy < -DIAGONAL) m = m or (1L shl if (j.arrows) Keys.UP else Keys.W)
            if (uy > DIAGONAL) m = m or (1L shl if (j.arrows) Keys.DOWN else Keys.S)
            if (ux < -DIAGONAL) m = m or (1L shl if (j.arrows) Keys.LEFT else Keys.A)
            if (ux > DIAGONAL) m = m or (1L shl if (j.arrows) Keys.RIGHT else Keys.D)
            val sprint = j.sprintKey.bit
            if (j.autoSprint && sprint >= 0 && len / r >= SPRINT_AT && uy < -0.7f) m = m or (1L shl sprint)
        }
        if (m != joyMask) {
            joyMask = m
            keysChanged()
        } else {
            invalidate()
        }
    }

    private fun hitButton(x: Float, y: Float): Int {
        for (i in controls.indices.reversed()) {
            val c = controls[i]
            if (c.kind == Kind.BUTTON && hypot(x - cx(c), y - cy(c)) <= radius(c) * 1.15f) return i
        }
        return -1
    }

    private fun keysChanged() {
        var m = joyMask
        for (c in controls) {
            if (c.kind != Kind.BUTTON) continue
            val bit = c.key.bit
            if (bit < 0) continue
            if (if (c.toggle) c.on else c.pointer >= 0) m = m or (1L shl bit)
        }
        gyro.buttons = m
        link.setButtons(m)
        invalidate()
    }

    private fun showMenu() {
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(store.current.name)
            .setItems(arrayOf("Edit layout", "Profiles (one per game)", "Settings", "Reconnect")) { _, which ->
                when (which) {
                    0 -> enterEdit()
                    1 -> Dialogs.profiles(context, store) { switched() }
                    2 -> openSettings()
                    3 -> link.reconnect()
                }
            }
            .show()
    }

    private fun openSettings() {
        Dialogs.settings(context, settings, gyro.available) {
            onSettingsChanged()
            invalidate()
        }
    }

    /** Called after the active profile changed or was replaced. */
    private fun switched() {
        role.fill(NONE)
        joyPointer = -1
        joyMask = 0
        keysChanged()
    }

    // ---- Edit mode ----

    fun enterEdit() {
        releaseAll()
        editing = true
        invalidate()
    }

    fun exitEdit() {
        editing = false
        dragIdx = -1
        store.save()
        invalidate()
    }

    private fun editTouch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIdx = -1
                val tool = toolRects.indexOfFirst { it.contains(x, y) }
                if (tool >= 0) {
                    toolbar(tool)
                    return
                }
                dragIdx = hitAny(x, y)
                if (dragIdx >= 0) {
                    dragDownX = x
                    dragDownY = y
                    dragOrigX = controls[dragIdx].x
                    dragOrigY = controls[dragIdx].y
                    dragMoved = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragIdx >= 0) {
                if (!dragMoved && hypot(x - dragDownX, y - dragDownY) > 8 * dp) dragMoved = true
                if (dragMoved) {
                    val c = controls[dragIdx]
                    c.x = (dragOrigX + (x - dragDownX) / width).coerceIn(0f, 1f)
                    c.y = (dragOrigY + (y - dragDownY) / height).coerceIn(0f, 1f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = dragIdx
                if (i >= 0 && !dragMoved) editControl(i)
            }
        }
    }

    private fun hitAny(x: Float, y: Float): Int {
        for (i in controls.indices.reversed()) {
            val c = controls[i]
            if (hypot(x - cx(c), y - cy(c)) <= max(radius(c), 18 * dp)) return i
        }
        return -1
    }

    private fun editControl(i: Int) {
        val c = controls[i]
        Dialogs.editControl(context, c, isNew = false, onSave = { invalidate() }, onDelete = {
            controls.removeAt(i)
            dragIdx = -1
            invalidate()
        })
    }

    private fun toolbar(tool: Int) {
        when (tool) {
            0 -> {
                val c = Control(Kind.BUTTON, "NEW", "E", 0.5f, 0.5f, 0.12f, Colors.GREY, 0.6f)
                Dialogs.editControl(context, c, isNew = true, onSave = {
                    controls.add(c)
                    invalidate()
                }, onDelete = null)
            }
            1 -> openSettings()
            2 -> AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset \"${store.current.name}\" to a preset")
                .setItems(Presets.all.map { it.first }.toTypedArray()) { _, which ->
                    controls.clear()
                    controls.addAll(Presets.all[which].second())
                    dragIdx = -1
                    invalidate()
                }
                .setNegativeButton("Cancel", null)
                .show()
            3 -> exitEdit()
        }
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        for (i in controls.indices) {
            val c = controls[i]
            if (c.kind == Kind.JOYSTICK) drawJoystick(canvas, c) else drawButton(canvas, c)
            if (editing && i == dragIdx) {
                ring.color = Color.WHITE
                ring.alpha = 255
                canvas.drawCircle(cx(c), cy(c), max(radius(c), 18 * dp) + 4 * dp, ring)
            }
        }

        // Settings gear.
        ring.color = Color.WHITE
        ring.alpha = 90
        canvas.drawCircle(gearX, gearY, 15 * dp, ring)
        label.textSize = 16 * dp
        label.alpha = 140
        canvas.drawText("⚙", gearX, gearY - (label.descent() + label.ascent()) / 2, label)

        // Status line at the bottom centre, below the buttons.
        val gyroText = when {
            !gyro.available || settings.gyroMode == GyroMode.OFF -> ""
            gyro.enabled -> "  ·  gyro on"
            else -> "  ·  gyro off"
        }
        canvas.drawText("${store.current.name}  ·  ${link.status}$gyroText", width / 2f, height - 6 * dp, small)

        if (editing) drawEditOverlay(canvas)
    }

    private fun drawButton(canvas: Canvas, c: Control) {
        val active = when {
            c.key.bit == Keys.GYRO_TOGGLE -> gyro.enabled
            c.toggle -> c.on
            else -> c.pointer >= 0
        }
        val x = cx(c)
        val y = cy(c)
        val r = radius(c)
        fill.color = c.color
        fill.alpha = ((if (active) 0.85f else c.alpha * 0.45f) * 255).toInt()
        canvas.drawCircle(x, y, r, fill)
        ring.color = c.color
        ring.alpha = ((if (active) 1f else c.alpha) * 255).toInt()
        canvas.drawCircle(x, y, r - ring.strokeWidth / 2, ring)
        label.textSize = (r * 0.42f).coerceIn(9 * dp, 20 * dp)
        label.alpha = ((if (active) 1f else max(c.alpha, 0.55f)) * 255).toInt()
        canvas.drawText(c.label, x, y - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawJoystick(canvas: Canvas, j: Control) {
        val r = radius(j)
        val held = joyPointer >= 0
        val bx = if (held) joyCx else cx(j)
        val by = if (held) joyCy else cy(j)
        fill.color = j.color
        fill.alpha = ((if (held) 0.18f else j.alpha * 0.12f) * 255).toInt()
        canvas.drawCircle(bx, by, r, fill)
        ring.color = j.color
        ring.alpha = ((if (held) 0.9f else j.alpha) * 255).toInt()
        canvas.drawCircle(bx, by, r, ring)
        fill.alpha = ((if (held) 0.8f else j.alpha * 0.6f) * 255).toInt()
        canvas.drawCircle(if (held) knobX else bx, if (held) knobY else by, r * 0.4f, fill)
        val sprint = j.sprintKey.bit
        if (held && sprint >= 0 && joyMask and (1L shl sprint) != 0L) {
            ring.color = Colors.LIME
            canvas.drawCircle(bx, by, r * 1.08f, ring)
        }
    }

    private fun drawEditOverlay(canvas: Canvas) {
        val j = joystick
        if (j != null && j.floating) {
            val zx = width * settings.joyZone
            canvas.drawLine(zx, 0f, zx, height.toFloat(), dashed)
            canvas.drawText("joystick area", zx / 2, height - 24 * dp, small)
        }
        val bw = 92 * dp
        val bh = 38 * dp
        val gap = 10 * dp
        var x = (width - (bw * tools.size + gap * (tools.size - 1))) / 2
        val y = height * 0.22f - bh / 2
        label.textSize = 14 * dp
        label.alpha = 255
        for (i in tools.indices) {
            toolRects[i].set(x, y, x + bw, y + bh)
            fill.color = if (i == tools.size - 1) Colors.GREEN else 0xFF37474F.toInt()
            fill.alpha = 235
            canvas.drawRoundRect(toolRects[i], 10 * dp, 10 * dp, fill)
            canvas.drawText(tools[i], toolRects[i].centerX(), toolRects[i].centerY() - (label.descent() + label.ascent()) / 2, label)
            x += bw + gap
        }
        canvas.drawText("Drag to move · tap a control to change it", width / 2f, y + bh + 18 * dp, small)
    }

    private companion object {
        const val MAX_POINTERS = 32
        const val NONE = -1
        const val AIM = -2
        const val JOY = -3
        const val GEAR = -4

        const val DEADZONE = 0.2f
        const val DIAGONAL = 0.38f     // sin(22.5°): 8-way movement
        const val SPRINT_AT = 0.95f
        const val COUNTS_PER_DP = 6f   // touch aim: mouse counts per dp at sensitivity 1.0
    }
}
