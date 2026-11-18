package com.example.clipkeys

import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

class KeyboardService : InputMethodService() {

    private val BG = 0xFF1C1C1E.toInt()
    private val KEY = 0xFF4A4A4C.toInt()
    private val SPECIAL = 0xFF2C2C2E.toInt()
    private val ACCENT = 0xFF0A84FF.toInt()

    private lateinit var store: ClipStore
    private lateinit var clipboard: ClipboardManager
    private lateinit var typingPane: LinearLayout
    private lateinit var clipPane: LinearLayout
    private lateinit var groupBar: LinearLayout
    private lateinit var clipList: LinearLayout
    private lateinit var tabAbc: TextView
    private lateinit var tabClip: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var shift = false
    private var capsLock = false
    private var symbols = false
    private var lastShiftTap = 0L
    private var currentGroup = 0L
    private var showingClips = false
    private var lastSaved = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { saveCurrentClip(0) }

    override fun onCreate() {
        super.onCreate()
        store = ClipStore(this)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::typingPane.isInitialized) {
            symbols = false; shift = false; capsLock = false
            renderKeys(); showPane(false)
        }
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabAbc = tab("ABC") { showPane(false) }
        tabClip = tab("Clipboard") { showPane(true) }
        tabs.addView(tabAbc, LinearLayout.LayoutParams(0, dp(36), 1f))
        tabs.addView(tabClip, LinearLayout.LayoutParams(0, dp(36), 1f))

        typingPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipPane = buildClipPane()

        root.addView(tabs)
        root.addView(typingPane)
        root.addView(clipPane)
        renderKeys()
        showPane(false)
        return root
    }

    private fun tab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f
        setOnClickListener { onClick() }
    }

    private fun showPane(clips: Boolean) {
        showingClips = clips
        typingPane.visibility = if (clips) View.GONE else View.VISIBLE
        clipPane.visibility = if (clips) View.VISIBLE else View.GONE
        tabAbc.setTextColor(if (!clips) ACCENT else Color.LTGRAY)
        tabClip.setTextColor(if (clips) ACCENT else Color.LTGRAY)
        if (clips) refreshClips()
    }

    // ---------- Typing pane ----------

    private val ACCENTS = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëēėę", 'i' to "îïíīįì", 'o' to "ôöòóœøōõ",
        'u' to "ûüùúū", 'c' to "çćč", 'n' to "ñń", 's' to "ßśš", 'y' to "ÿý",
        'z' to "žźż", 'l' to "ł", 'd' to "ð", 't' to "þ"
    )
    private var previewPopup: PopupWindow? = null
    private var accentPopup: PopupWindow? = null
    private val accentViews = mutableListOf<TextView>()
    private var accentIndex = -1

    private fun key(
        label: String, weight: Float, special: Boolean = false, accent: Boolean = false,
        repeat: Boolean = false, letter: Boolean = false, alts: String = "", onPress: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label; tv.gravity = Gravity.CENTER; tv.setTextColor(Color.WHITE); tv.textSize = 20f
        tv.background = round(if (accent) ACCENT else if (special) SPECIAL else KEY, 8)
        tv.layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
        var repeater: Runnable? = null
        var longPress: Runnable? = null
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (letter) {
                        // Letters commit on release so a long-press can open the accent picker.
                        showPreview(v, label)
                        if (alts.isNotEmpty()) {
                            val r = Runnable { dismissPreview(); showAccents(v, alts) }
                            longPress = r
                            handler.postDelayed(r, 350)
                        }
                    } else {
                        onPress()
                        if (repeat) {
                            val r = object : Runnable {
                                override fun run() { onPress(); handler.postDelayed(this, 50) }
                            }
                            repeater = r
                            handler.postDelayed(r, 400)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (accentPopup != null) updateAccentSelection(e.rawX)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    repeater?.let { handler.removeCallbacks(it) }; repeater = null
                    longPress?.let { handler.removeCallbacks(it) }; longPress = null
                    if (letter) {
                        dismissPreview()
                        val up = e.actionMasked == MotionEvent.ACTION_UP
                        if (accentPopup != null) {
                            val pick = accentIndex
                            dismissAccents()
                            if (up) {
                                if (pick in alts.indices) typeText(alts[pick].toString()) else onPress()
                            }
                        } else if (up) onPress()
                    }
                }
            }
            true
        }
        return tv
    }

    private fun showPreview(anchor: View, text: String) {
        dismissPreview()
        val tv = TextView(this).apply {
            this.text = text; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = round(0xFF6A6A6C.toInt(), 10)
        }
        val h = dp(64)
        val p = PopupWindow(tv, anchor.width + dp(16), h, false).apply { isTouchable = false }
        p.showAsDropDown(anchor, -dp(8), -(anchor.height + h) + dp(4))
        previewPopup = p
    }

    private fun dismissPreview() { previewPopup?.dismiss(); previewPopup = null }

    private fun showAccents(anchor: View, alts: String) {
        dismissAccents()
        accentViews.clear()
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(0xFF3A3A3C.toInt(), 10)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        alts.forEach { c ->
            val t = TextView(this).apply {
                text = c.toString(); textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(52))
            }
            accentViews.add(t); rowView.addView(t)
        }
        val h = dp(60)
        val p = PopupWindow(rowView, dp(8) + dp(40) * alts.length, h, false).apply { isTouchable = false }
        p.showAsDropDown(anchor, 0, -(anchor.height + h))
        accentPopup = p
        accentIndex = -1
    }

    private fun updateAccentSelection(rawX: Float) {
        var idx = -1
        accentViews.forEachIndexed { i, t ->
            val loc = IntArray(2); t.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX < loc[0] + t.width) idx = i
        }
        if (idx != accentIndex) {
            accentIndex = idx
            accentViews.forEachIndexed { i, t -> t.background = if (i == idx) round(ACCENT, 8) else null }
        }
    }

    private fun dismissAccents() { accentPopup?.dismiss(); accentPopup = null; accentIndex = -1 }

    override fun onWindowHidden() {
        dismissPreview(); dismissAccents()
        super.onWindowHidden()
    }

    private fun spacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(46), weight)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun renderKeys() {
        typingPane.removeAllViews()
        val r1 = if (symbols) "1234567890" else "qwertyuiop"
        val r2 = if (symbols) "-/:;()\$&@\"" else "asdfghjkl"
        val r3 = if (symbols) "!?'#%+=" else "zxcvbnm"

        fun chars(s: String) = s.map { c ->
            val label = if (!symbols && (shift || capsLock)) c.uppercase() else c.toString()
            val alts = if (symbols) "" else (ACCENTS[c] ?: "").let { if (shift || capsLock) it.uppercase() else it }
            key(label, 1f, letter = !symbols, alts = alts) { typeText(label) }
        }

        typingPane.addView(row(*chars(r1).toTypedArray()))

        val second = chars(r2).toMutableList<View>()
        if (!symbols) { second.add(0, spacer(0.5f)); second.add(spacer(0.5f)) }
        typingPane.addView(row(*second.toTypedArray()))

        val left: View = if (symbols) spacer(1.5f) else key(
            if (capsLock) "⇪" else "⇧", 1.5f, special = true, accent = shift || capsLock
        ) { onShift() }
        val back = key("⌫", 1.5f, special = true, repeat = true) { backspace() }
        typingPane.addView(row(left, *chars(r3).toTypedArray(), back))

        typingPane.addView(
            row(
                key(if (symbols) "ABC" else "?123", 1.5f, special = true) { symbols = !symbols; renderKeys() },
                key(",", 1f) { typeText(",") },
                key(" ", 5f) { typeText(" ") },
                key(".", 1f) { typeText(".") },
                key("⏎", 1.5f, accent = true) { enter() }
            )
        )
    }

    private fun onShift() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTap < 350) { capsLock = true; shift = true }
        else if (capsLock) { capsLock = false; shift = false }
        else shift = !shift
        lastShiftTap = now
        renderKeys()
    }

    private fun typeText(s: String) {
        currentInputConnection?.commitText(s, 1)
        if (shift && !capsLock) { shift = false; renderKeys() }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (TextUtils.isEmpty(ic.getSelectedText(0))) ic.deleteSurroundingTextInCodePoints(1, 0)
        else ic.commitText("", 1)
    }

    private fun enter() {
        val ei = currentInputEditorInfo
        val noAction = ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noAction || !sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n", 1)
    }

    // ---------- Clipboard pane ----------

    private fun buildClipPane(): LinearLayout {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        groupBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(groupBar)
        val scroll = ScrollView(this)
        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(clipList)
        pane.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        pane.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
        return pane
    }

    private fun chip(label: String, selected: Boolean) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = round(if (selected) ACCENT else SPECIAL, 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
    }

    private fun saveCurrentClip(groupId: Long) {
        val t = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) return
        if (groupId == 0L && t == lastSaved) return
        lastSaved = t
        store.addClip(t, groupId)
        if (showingClips) refreshClips()
    }

    private fun refreshClips() {
        val groups = store.groups()
        groupBar.removeAllViews()

        groupBar.addView(chip("Recent", currentGroup == 0L).apply {
            setOnClickListener { currentGroup = 0L; refreshClips() }
        })
        groups.forEach { g ->
            groupBar.addView(chip(g.name, currentGroup == g.id).apply {
                setOnClickListener { currentGroup = g.id; refreshClips() }
                setOnLongClickListener {
                    PopupMenu(this@KeyboardService, this).apply {
                        menu.add("Delete group (clips go to Recent)")
                        setOnMenuItemClickListener {
                            store.deleteGroup(g.id)
                            if (currentGroup == g.id) currentGroup = 0L
                            refreshClips(); true
                        }
                    }.show()
                    true
                }
            })
        }
        groupBar.addView(chip("＋ Group", false).apply {
            setOnClickListener {
                currentGroup = store.addGroup("Group ${groups.size + 1}")
                refreshClips()
            }
        })
        groupBar.addView(chip("⎘ Save clipboard here", false).apply {
            setOnClickListener { saveCurrentClip(currentGroup) }
        })

        clipList.removeAllViews()
        val clips = store.clips(currentGroup)
        if (clips.isEmpty()) {
            clipList.addView(TextView(this).apply {
                text = "Nothing here yet. Copy something, or tap \"Save clipboard here\"."
                setTextColor(Color.GRAY); setPadding(dp(12), dp(16), dp(12), dp(16))
            })
        }
        clips.forEach { c -> clipList.addView(clipRow(c, groups)) }
    }

    private fun clipRow(c: Clip, groups: List<Group>) = TextView(this).apply {
        text = (if (c.pinned) "📌 " else "") + c.text
        setTextColor(Color.WHITE); textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = round(SPECIAL, 10)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        setOnClickListener { currentInputConnection?.commitText(c.text, 1) }
        setOnLongClickListener {
            PopupMenu(this@KeyboardService, this).apply {
                menu.add(0, 1, 0, if (c.pinned) "Unpin" else "Pin")
                if (c.groupId != 0L) menu.add(0, 2, 1, "Move to Recent")
                groups.filter { it.id != c.groupId }.forEachIndexed { i, g ->
                    menu.add(1, 100 + i, 2 + i, "Move to ${g.name}")
                }
                menu.add(0, 3, 99, "Delete")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> store.setPinned(c.id, !c.pinned)
                        2 -> store.move(c.id, 0)
                        3 -> store.delete(c.id)
                        else -> store.move(c.id, groups.filter { it.id != c.groupId }[item.itemId - 100].id)
                    }
                    refreshClips(); true
                }
            }.show()
            true
        }
    }
}
