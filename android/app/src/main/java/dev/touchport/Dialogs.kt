package dev.touchport

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

object Dialogs {
    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun editControl(ctx: Context, c: Control, isNew: Boolean, onSave: () -> Unit, onDelete: (() -> Unit)?) {
        val edit = c.copy()
        val box = column(ctx)

        val name = EditText(ctx).apply {
            setText(edit.label)
            setSingleLine()
            hint = "Label"
        }
        if (edit.kind == Kind.BUTTON) {
            box.addView(caption(ctx, "Label"))
            box.addView(name)
            box.addView(caption(ctx, "Key it presses"))
            box.addView(keySpinner(ctx, edit.key) { edit.keyId = it.id })
        }

        box.addView(caption(ctx, "Colour"))
        box.addView(palette(ctx, edit.color) { edit.color = it })

        box.addView(slider(ctx, "Size", 5, 50, (edit.size * 100).toInt(), { "$it%" }) { edit.size = it / 100f })
        box.addView(slider(ctx, "Opacity", 15, 100, (edit.alpha * 100).toInt(), { "$it%" }) { edit.alpha = it / 100f })

        if (edit.kind == Kind.BUTTON) {
            box.addView(check(ctx, "Toggle (tap on, tap off) instead of hold", edit.toggle) { edit.toggle = it })
            box.addView(check(ctx, "Drag on this button also aims", edit.aimDrag) { edit.aimDrag = it })
        } else {
            box.addView(check(ctx, "Floating (appears where your thumb lands)", edit.floating) { edit.floating = it })
            box.addView(check(ctx, "Arrow keys instead of W A S D", edit.arrows) { edit.arrows = it })
            box.addView(check(ctx, "Auto-sprint when pushed fully forward", edit.autoSprint) { edit.autoSprint = it })
            box.addView(caption(ctx, "Sprint key"))
            box.addView(keySpinner(ctx, edit.sprintKey) { edit.sprintKeyId = it.id })
        }

        AlertDialog.Builder(ctx, THEME)
            .setTitle(if (edit.kind == Kind.JOYSTICK) "Joystick" else if (isNew) "New button" else "Button")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(if (isNew) "Add" else "Save") { _, _ ->
                edit.label = name.text.toString().trim().ifEmpty { edit.key.label }.uppercase()
                c.copyFrom(edit)
                onSave()
            }
            .setNegativeButton("Cancel", null)
            .apply { if (onDelete != null && c.kind == Kind.BUTTON) setNeutralButton("Delete") { _, _ -> onDelete() } }
            .show()
    }

    /** Lists the profiles. Tap one to switch to it. */
    fun profiles(ctx: Context, store: ProfileStore, onChanged: () -> Unit) {
        AlertDialog.Builder(ctx, THEME)
            .setTitle("Profiles")
            .setSingleChoiceItems(store.profiles.map { it.name }.toTypedArray(), store.active) { d, which ->
                store.select(which)
                onChanged()
                d.dismiss()
            }
            .setPositiveButton("New") { _, _ -> newProfile(ctx, store, onChanged) }
            .setNeutralButton("Manage") { _, _ -> manageProfile(ctx, store, onChanged) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun newProfile(ctx: Context, store: ProfileStore, onChanged: () -> Unit) {
        val sources = listOf("Copy of \"${store.current.name}\"") + Presets.all.map { "Preset: ${it.first}" }
        AlertDialog.Builder(ctx, THEME)
            .setTitle("Start from")
            .setItems(sources.toTypedArray()) { _, which ->
                val controls = if (which == 0) store.current.controls.mapTo(mutableListOf()) { it.copy() }
                else Presets.all[which - 1].second()
                val suggested = if (which == 0) store.current.name else "My game"
                askText(ctx, "Profile name (for example the game)", suggested) { name ->
                    store.select(store.add(name, controls))
                    onChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manageProfile(ctx: Context, store: ProfileStore, onChanged: () -> Unit) {
        val name = store.current.name
        AlertDialog.Builder(ctx, THEME)
            .setTitle(name)
            .setItems(arrayOf("Rename", "Share this profile", "Import from clipboard", "Delete")) { _, which ->
                when (which) {
                    0 -> askText(ctx, "New name", name) { store.rename(store.active, it); onChanged() }
                    1 -> {
                        val text = store.toJson(listOf(store.current)).toString(2)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "TouchPort profile: $name")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        ctx.startActivity(android.content.Intent.createChooser(send, "Share profile"))
                    }
                    2 -> {
                        val clip = ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        val msg = try {
                            val n = store.importJson(text ?: "", replace = false)
                            onChanged()
                            "Imported $n profile(s)"
                        } catch (e: Exception) {
                            "The clipboard has no TouchPort profile"
                        }
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    3 -> if (store.profiles.size <= 1) {
                        android.widget.Toast.makeText(ctx, "You need at least one profile", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(ctx, THEME)
                            .setMessage("Delete the profile \"$name\"?")
                            .setPositiveButton("Delete") { _, _ -> store.delete(store.active); onChanged() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askText(ctx: Context, title: String, value: String, onOk: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(value)
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(ctx, THEME)
            .setTitle(title)
            .setView(column(ctx).apply { addView(input) })
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun settings(ctx: Context, s: Settings, gyroAvailable: Boolean, onSave: () -> Unit) {
        val box = column(ctx)

        // Connection
        box.addView(header(ctx, "Connection"))
        val modes = LinkMode.entries
        val modeLabels = listOf(
            "Auto: USB (ADB) when plugged in, else Wi-Fi / USB tethering",
            "USB with ADB (USB debugging on)",
            "Manual IP (Wi-Fi)",
        )
        var linkMode = s.linkMode
        val ip = EditText(ctx).apply {
            setText(s.manualIp)
            hint = "PC IP address, e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            visibility = if (linkMode == LinkMode.MANUAL) View.VISIBLE else View.GONE
        }
        box.addView(radios(ctx, modeLabels, modes.indexOf(linkMode)) {
            linkMode = modes[it]
            ip.visibility = if (linkMode == LinkMode.MANUAL) View.VISIBLE else View.GONE
        })
        box.addView(ip)

        // Touch aim
        box.addView(header(ctx, "Touch aim"))
        var sens = s.sens
        var invertY = s.invertY
        box.addView(slider(ctx, "Sensitivity", 5, 500, (sens * 100).toInt(), { "%.2f".format(it / 100f) }) { sens = it / 100f })
        box.addView(check(ctx, "Invert vertical", invertY) { invertY = it })

        // Gyro
        box.addView(header(ctx, "Gyro aim"))
        var gyroMode = s.gyroMode
        var gyroSens = s.gyroSens
        var gyroRoll = s.gyroRoll
        var gyroInvX = s.gyroInvertX
        var gyroInvY = s.gyroInvertY
        if (!gyroAvailable) {
            box.addView(caption(ctx, "This phone has no gyroscope."))
        } else {
            val gmodes = GyroMode.entries
            box.addView(radios(ctx, listOf("Off", "Always on", "While aiming (ADS held)", "While firing or aiming"),
                gmodes.indexOf(gyroMode)) { gyroMode = gmodes[it] })
            box.addView(slider(ctx, "Gyro sensitivity", 5, 500, (gyroSens * 100).toInt(), { "%.2f".format(it / 100f) }) {
                gyroSens = it / 100f
            })
            box.addView(check(ctx, "Turn left/right by tilting (roll) instead of turning (yaw)", gyroRoll) { gyroRoll = it })
            box.addView(check(ctx, "Invert gyro horizontal", gyroInvX) { gyroInvX = it })
            box.addView(check(ctx, "Invert gyro vertical", gyroInvY) { gyroInvY = it })
            box.addView(caption(ctx, "Tip: map a button to \"Gyro on / off\" to pause gyro quickly."))
        }

        // HUD
        box.addView(header(ctx, "HUD"))
        var joyZone = s.joyZone
        var haptics = s.haptics
        box.addView(slider(ctx, "Floating joystick area", 20, 60, (joyZone * 100).toInt(), { "$it% of width" }) {
            joyZone = it / 100f
        })
        box.addView(check(ctx, "Vibrate on button press", haptics) { haptics = it })

        AlertDialog.Builder(ctx, THEME)
            .setTitle("Settings")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                s.linkMode = linkMode
                s.manualIp = ip.text.toString().trim()
                s.sens = sens
                s.invertY = invertY
                s.gyroMode = gyroMode
                s.gyroSens = gyroSens
                s.gyroRoll = gyroRoll
                s.gyroInvertX = gyroInvX
                s.gyroInvertY = gyroInvY
                s.joyZone = joyZone
                s.haptics = haptics
                s.save()
                onSave()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Small view helpers ----

    private fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = ctx.px(20)
        setPadding(p, ctx.px(8), p, ctx.px(8))
    }

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, ctx.px(16), 0, ctx.px(4))
    }

    private fun caption(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        alpha = 0.75f
        setPadding(0, ctx.px(10), 0, ctx.px(2))
    }

    private fun check(ctx: Context, text: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(ctx).apply {
        this.text = text
        isChecked = value
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun radios(ctx: Context, labels: List<String>, selected: Int, onChange: (Int) -> Unit) = RadioGroup(ctx).apply {
        labels.forEachIndexed { i, l ->
            addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                text = l
                isChecked = i == selected
                setOnClickListener { onChange(i) }
            })
        }
    }

    private fun slider(
        ctx: Context, title: String, min: Int, max: Int, value: Int,
        format: (Int) -> String, onChange: (Int) -> Unit,
    ): View {
        val text = TextView(ctx).apply {
            this.text = "$title: ${format(value)}"
            setPadding(0, ctx.px(10), 0, 0)
        }
        val bar = SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    text.text = "$title: ${format(v)}"
                    onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(bar)
        }
    }

    private fun keySpinner(ctx: Context, selected: Key, onPick: (Key) -> Unit) = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, Keys.all.map { it.label })
        setSelection(Keys.all.indexOf(selected))
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onPick(Keys.all[pos])
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun palette(ctx: Context, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val size = ctx.px(34)
        val swatches = mutableListOf<View>()
        fun paint(v: View, col: Int, on: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col)
                setStroke(ctx.px(if (on) 3 else 1), if (on) Color.WHITE else 0x60FFFFFF)
            }
        }
        for (col in Colors.palette) {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(ctx.px(3), ctx.px(4), ctx.px(3), ctx.px(4)) }
            paint(v, col, col == selected)
            v.setOnClickListener {
                Colors.palette.forEachIndexed { i, other -> paint(swatches[i], other, other == col) }
                onPick(col)
            }
            swatches += v
            row.addView(v)
        }
        return HorizontalScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }
}
