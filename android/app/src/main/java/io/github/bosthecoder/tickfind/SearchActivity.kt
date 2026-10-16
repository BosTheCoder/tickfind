package io.github.bosthecoder.tickfind

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The search box, floating over whatever app you were in. Type to search; tap a match for the quick editor;
 * "Open in TickTick" from there. The launcher icon, the bubble, the Quick Settings tile and the assistant gesture all land here.
 */
class SearchActivity : Activity() {
    private lateinit var store: Store
    private lateinit var tt: TickTick
    private lateinit var card: LinearLayout
    private lateinit var searchPage: LinearLayout
    private lateinit var box: EditText
    private lateinit var expandIcon: android.widget.ImageView
    private lateinit var scroll: MaxScroll
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var note: String? = null
    private var server: List<TickTick.Found>? = null
    private var firstOpen: (() -> Unit)? = null

    // The editor, and the task as TickTick had it when it loaded (to work out what changed)
    private lateinit var editPage: LinearLayout
    private lateinit var editList: TextView
    private lateinit var editTitle: EditText
    private lateinit var priorityRow: LinearLayout
    private lateinit var editContent: EditText
    private lateinit var itemsBox: LinearLayout
    private lateinit var addItem: EditText
    private lateinit var banner: LinearLayout
    private lateinit var editStatus: TextView
    private lateinit var saveButton: Button
    private lateinit var completeButton: Button
    private var editing: TTask? = null
    private var loaded: JSONObject? = null
    private var editPriority = 0
    private var editItems = mutableListOf<JSONObject>()

    private val onSync: () -> Unit = { if (editing == null) render() else renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        tt = TickTick(store)
        if (intent.getBooleanExtra("demo", false)) Repo.startDemo()
        build()
        intent.getStringExtra("query")?.let { box.setText(it) }
        Repo.listen(onSync)
        summon()
        if (intent.getBooleanExtra("edit", false)) firstOpen?.invoke()
    }

    override fun onDestroy() { Repo.unlisten(onSync); super.onDestroy() }

    /** Opened again (bubble, tile, icon): back to the box with the last search selected, unless there are unsaved edits. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (editing != null && changes().length() == 0) showSearch()
        summon()
    }

    private fun summon() {
        if (editing == null) {
            box.requestFocus(); box.selectAll()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        render()
        if (Repo.stale(this)) Repo.sync(this)
    }

    // ---------- Layout ----------

    private fun build() {
        val pad = dp(14)
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(CARD, dp(20).toFloat()); setPadding(pad, dp(8), pad, dp(10))
        }
        searchPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(FAINT)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        box = field(single = true).apply {
            hint = "Search TickTick"; textSize = 18f; background = null; setPadding(0, dp(10), 0, dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> firstOpen?.invoke(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { server = null; render() }
            })
        }
        bar.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        expandIcon = icon(R.drawable.ic_expand, 18) { store.expanded = !store.expanded; render() }
        bar.addView(expandIcon)
        bar.addView(icon(R.drawable.ic_settings, 20) { startActivity(Intent(this, MainActivity::class.java)) })
        searchPage.addView(bar)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = MaxScroll(this).apply { addView(results); isVerticalScrollBarEnabled = false }
        searchPage.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = text("", 12f, FAINT).apply { setPadding(dp(4), dp(8), dp(4), 0); maxLines = 2; setOnClickListener { if (note == null) openSettings() } }
        searchPage.addView(status)
        card.addView(searchPage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        buildEditor()
        card.addView(editPage, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(card)
        window.setGravity(Gravity.TOP)
        window.attributes = window.attributes.apply { y = dp(28) }
        size()
    }

    /** Compact: as tall as the matches. Bigger or editing: the whole screen above the keyboard. */
    private fun size() {
        val big = editing != null || store.expanded
        window.setLayout(resources.displayMetrics.widthPixels - dp(20), if (big) MATCH_PARENT else WRAP_CONTENT)
        scroll.maxH = if (big) Int.MAX_VALUE else (resources.displayMetrics.heightPixels * 0.5).toInt()
        (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = if (big) 0 else WRAP_CONTENT; weight = if (big) 1f else 0f }
        (searchPage.layoutParams as LinearLayout.LayoutParams).height = if (big) MATCH_PARENT else WRAP_CONTENT
        expandIcon.setImageResource(if (store.expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
    }

    private fun field(single: Boolean = false) = EditText(this).apply {
        setTextColor(TEXT); setHintTextColor(FAINT); textSize = 15f
        background = rounded(FIELD, dp(10).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
        if (single) { isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
    }

    private fun button(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(TEXT); textSize = 14f; minHeight = 0; minimumHeight = 0
        background = rounded(bg, dp(10).toFloat()); setPadding(dp(14), dp(9), dp(14), dp(9)); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    // ---------- Search ----------

    private fun render() {
        if (!::results.isInitialized) return
        size()
        results.removeAllViews()
        firstOpen = null
        val index = Repo.index(this)
        val max = if (store.expanded) 30 else store.results
        val q = box.text.toString().trim()
        when {
            !Repo.demo && !store.signedIn ->
                row(R.drawable.ic_search, "Sign in to TickTick", "In Settings. Tickfind asks to read and edit your tasks, nothing else.") { openSettings() }
            server != null -> {
                row(R.drawable.ic_back, "TickTick's results for “$q”", "Completed tasks included. Tap to go back.") { server = null; render() }
                for (f in server!!.take(30)) row(R.drawable.ic_check, f.title, index.lists[f.projectId] ?: "") { openFound(f) }
                if (server!!.isEmpty()) row(R.drawable.ic_search, "TickTick found nothing either", "") {}
            }
            else -> {
                val pool = store.excludeLists.let { ex -> if (ex.isEmpty()) index.tasks else index.tasks.filter { it.list !in ex } }
                val hits = Search.run(pool, "${store.defaultFilter} $q".trim(), store.sort)
                val terms = Search.parse(q).terms
                for (h in hits.take(max)) taskRow(h.task, terms)
                if (hits.isNotEmpty()) firstOpen = { openEditor(hits[0].task) }
                if (hits.size > max) row(R.drawable.ic_expand, "${hits.size - max} more", if (store.expanded) "Add a word or a filter to narrow it down" else "Tap to show more") {
                    if (!store.expanded) { store.expanded = true; render() }
                }
                if (terms.isNotEmpty()) row(R.drawable.ic_search, "Search all of TickTick for “$q”", "TickTick's own search, completed tasks included") { serverSearch(q) }
                if (index.tasks.isEmpty()) row(R.drawable.ic_search, if (Repo.syncing) "Reading your tasks from TickTick…" else "Nothing indexed yet", "Tap to sync now") { Repo.sync(this) }
            }
        }
        renderStatus()
    }

    private fun renderStatus() {
        val i = Repo.index(this)
        status.text = when {
            Repo.syncing -> "Syncing with TickTick…"
            note != null -> note
            Repo.error != null -> Repo.error
            Repo.demo -> "Demo: made-up tasks, nothing is saved"
            i.synced == 0L -> ""
            else -> "%,d tasks · synced %s".format(i.tasks.size, ago(i.synced))
        }
    }

    private fun taskRow(t: TTask, terms: List<String>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(marked(t.title.ifEmpty { "(no title)" }, terms), 16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (terms.isNotEmpty() && !terms.all { it in Search.fold(t.title) })
            Search.snippet(t.content + " " + t.items.joinToString(" · "), terms, 70)?.let {
                col.addView(text(marked(it, terms), 13f, 0xBFF2F2F4.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
            }
        val meta = android.text.SpannableStringBuilder(t.list)
        dueText(t.due)?.let { (s, late) ->
            meta.append("  ·  ")
            val at = meta.length
            meta.append(s)
            if (late) meta.setSpan(android.text.style.ForegroundColorSpan(LATE), at, meta.length, 0)
        }
        t.tags.forEach { meta.append("  #$it") }
        if (t.kind == "NOTE") meta.append("  ·  note")
        col.addView(text(meta, 12f, DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        val rowView = LinearLayout(this).apply {
            setPadding(dp(8), dp(9), dp(8), dp(9)); background = ripple(); isClickable = true
            setOnClickListener { openEditor(t) }
            setOnLongClickListener { TickTick.open(this@SearchActivity, t.projectId, t.id, store.web); finish(); true }
            addView(View(context).apply { background = rounded(priorityColor(t.priority), dp(2).toFloat()) },
                LinearLayout.LayoutParams(dp(3), MATCH_PARENT).apply { marginEnd = dp(10) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        results.addView(rowView)
    }

    private fun row(iconRes: Int, title: String, sub: String, onClick: () -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) col.addView(text(sub, 12f, FAINT).apply { maxLines = 2 })
        results.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(9), dp(8), dp(9)); background = ripple(); isClickable = true
            setOnClickListener { onClick() }
            addView(android.widget.ImageView(context).apply { setImageResource(iconRes); imageTintList = ColorStateList.valueOf(FAINT) },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12); marginStart = dp(2) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
    }

    private fun serverSearch(q: String) {
        if (Repo.demo) { note = "The demo can't ask TickTick"; renderStatus(); return }
        note = "Asking TickTick…"; renderStatus()
        thread {
            val r = runCatching { tt.serverSearch(q) }
            runOnUiThread {
                note = r.exceptionOrNull()?.let { "TickTick's search failed: ${it.message}" }
                if (box.text.toString().trim() == q) server = r.getOrNull()
                render()
            }
        }
    }

    /** A task from TickTick's own search isn't in the index (it may be completed), so load it first. */
    private fun openFound(f: TickTick.Found) {
        note = "Opening…"; renderStatus()
        thread {
            val r = runCatching { tt.get(f.id) }
            runOnUiThread {
                note = null
                r.onSuccess { j -> openEditor(TickTick.parse(j, Repo.index(this).lists[f.projectId] ?: ""), j) }
                    .onFailure { note = "Couldn't open it: ${it.message}"; renderStatus() }
            }
        }
    }

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

    // ---------- Quick edit ----------

    private fun buildEditor() {
        editPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(R.drawable.ic_back, 20) { back() })
        editList = text("", 13f, DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(editList, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(button("Open in TickTick", 0x22FFFFFF) {
            editing?.let { TickTick.open(this, it.projectId, it.id, store.web); finish() }
        })
        editPage.addView(top)
        editTitle = field().apply { textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        editPage.addView(editTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        priorityRow = LinearLayout(this)
        editPage.addView(priorityRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editContent = field().apply {
            gravity = Gravity.TOP; minLines = 4; hint = "Notes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        body.addView(editContent)
        itemsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(itemsBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        addItem = field(single = true).apply {
            hint = "Add an item"; imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                text.toString().trim().takeIf { it.isNotEmpty() }?.let { editItems += JSONObject().put("title", it).put("status", 0); setText(""); renderItems() }
                true
            }
        }
        body.addView(addItem, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        editPage.addView(android.widget.ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = rounded(0x40FAA80C, dp(10).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        editPage.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        completeButton = button("✓  Complete", 0x22FFFFFF) { complete() }
        bottom.addView(completeButton)
        editStatus = text("", 12f, DIM).apply { maxLines = 2; setPadding(dp(10), 0, dp(10), 0) }
        bottom.addView(editStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        saveButton = button("Save", BLUE) { save(force = false) }
        bottom.addView(saveButton)
        editPage.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun openEditor(t: TTask, fresh: JSONObject? = null) {
        editing = t; loaded = null; editItems = mutableListOf()
        editList.text = t.list; editTitle.setText(t.title); editContent.setText(t.content); editPriority = t.priority
        itemsBox.removeAllViews(); addItem.visibility = View.GONE; banner.visibility = View.GONE; completeButton.visibility = View.VISIBLE
        renderPriority(); editable(false)
        searchPage.visibility = View.GONE; editPage.visibility = View.VISIBLE
        setFinishOnTouchOutside(false)   // a tap outside mustn't throw away what you typed
        size()
        when {
            Repo.demo -> {
                fill(JSONObject().put("id", t.id).put("projectId", t.projectId).put("title", t.title).put("priority", t.priority).put("kind", t.kind).put("status", 0)
                    .put(if (t.kind == "CHECKLIST") "desc" else "content", t.content)
                    .put("items", JSONArray(t.items.mapIndexed { i, s -> JSONObject().put("id", "i$i").put("title", s).put("status", 0).put("sortOrder", i) })))
                editStatus.text = "Demo: nothing reaches TickTick"
            }
            fresh != null -> fill(fresh)
            else -> {
                editStatus.text = "Loading the latest…"
                thread {
                    val r = runCatching { tt.get(t.id) }
                    runOnUiThread {
                        r.onSuccess { fill(it) }.onFailure {
                            editStatus.text = if (it is NeedsSignIn) "TickTick signed you out: sign in again in Settings" else "Couldn't load it: ${it.message}"
                        }
                    }
                }
            }
        }
    }

    /** Shows the task as TickTick has it now. Fields stay locked until this, so nothing typed meanwhile is lost. */
    private fun fill(j: JSONObject) {
        if (editing?.id != j.optString("id")) return
        loaded = j
        val checklist = j.str("kind") == "CHECKLIST"
        editTitle.setText(j.str("title"))
        editContent.setText(j.str(if (checklist) "desc" else "content"))
        editPriority = j.optInt("priority")
        editItems = j.optJSONArray("items")?.let { a -> (0 until a.length()).map { JSONObject(a.getJSONObject(it).toString()) }.toMutableList() } ?: mutableListOf()
        addItem.visibility = if (checklist) View.VISIBLE else View.GONE
        val open = j.optInt("status") == 0
        completeButton.visibility = if (open) View.VISIBLE else View.GONE
        editStatus.text = if (open) "" else "Completed"
        renderPriority(); renderItems(); editable(true)
        editContent.requestFocus(); editContent.setSelection(editContent.text.length)
    }

    private fun editable(on: Boolean) {
        editTitle.isEnabled = on; editContent.isEnabled = on; addItem.isEnabled = on
        saveButton.isEnabled = on; completeButton.isEnabled = on
        saveButton.alpha = if (on) 1f else 0.4f; completeButton.alpha = if (on) 1f else 0.4f
    }

    private fun renderPriority() {
        priorityRow.removeAllViews()
        for ((label, p) in listOf("None" to 0, "Low" to 1, "Medium" to 3, "High" to 5)) {
            val chip = text("●  $label", 13f).apply {
                val s = android.text.SpannableString(text); s.setSpan(android.text.style.ForegroundColorSpan(priorityColor(p)), 0, 1, 0); text = s
                background = rounded(if (p == editPriority) 0x55FFFFFF else 0x16FFFFFF, dp(8).toFloat())
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { if (loaded != null) { editPriority = p; renderPriority() } }
            }
            priorityRow.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun renderItems() {
        itemsBox.removeAllViews()
        for (item in editItems) {
            val done = item.optInt("status") > 0
            itemsBox.addView(CheckBox(this).apply {
                text = item.str("title"); setTextColor(if (done) FAINT else TEXT); textSize = 15f; isChecked = done
                paintFlags = if (done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                buttonTintList = ColorStateList.valueOf(BLUE)
                setOnClickListener { item.put("status", if (isChecked) 1 else 0); renderItems() }
            })
        }
    }

    /** Only the fields you changed, in the shape update_task wants. */
    private fun changes(): JSONObject {
        val c = JSONObject()
        val l = loaded ?: return c
        val checklist = l.str("kind") == "CHECKLIST"
        val field = if (checklist) "desc" else "content"
        if (editTitle.text.toString() != l.str("title")) c.put("title", editTitle.text.toString())
        if (editContent.text.toString() != l.str(field)) c.put(field, editContent.text.toString())
        if (editPriority != l.optInt("priority")) c.put("priority", editPriority)
        if (checklist) {
            val items = JSONArray(editItems.mapIndexed { i, x -> JSONObject(x.toString()).also { if (!it.has("sortOrder")) it.put("sortOrder", i) } })
            if (items.toString() != (l.optJSONArray("items") ?: JSONArray()).toString()) c.put("items", items)
        }
        return c
    }

    private fun save(force: Boolean) {
        val t = editing ?: return
        val l = loaded ?: return
        val c = changes()
        if (c.length() == 0) { showSearch(); return }
        if (Repo.demo) { ask("This is the demo, so nothing was saved.", "OK", ::showSearch, "Keep editing") {}; return }
        editable(false); editStatus.text = "Saving…"; banner.visibility = View.GONE
        thread {
            val r = runCatching {
                // TickTick accepts a stale etag, so check for a change made elsewhere ourselves before overwriting it.
                if (!force) tt.get(t.id).let { now -> if (now.str("etag") != l.str("etag")) return@runCatching now }
                Repo.put(this, TickTick.parse(tt.update(t.id, l.getString("projectId"), c), t.list))
                null
            }
            runOnUiThread {
                r.onSuccess { theirs ->
                    if (theirs != null) {
                        editable(true); editStatus.text = ""
                        ask("This task changed in TickTick since you opened it. Saving puts your version of the fields you edited over theirs.",
                            "Save mine", { save(force = true) }, "Show theirs") { fill(theirs) }
                    } else { note = "Saved"; showSearch() }
                }.onFailure {
                    editable(true)
                    editStatus.text = if (it is NeedsSignIn) "TickTick signed you out. Copy your text, then sign in again in Settings." else "Didn't save: ${it.message}"
                }
            }
        }
    }

    private fun complete() {
        val t = editing ?: return
        val l = loaded ?: return
        if (Repo.demo) { Repo.remove(this, t.id); showSearch(); return }
        val c = changes()
        editable(false); editStatus.text = "Completing…"
        thread {
            val r = runCatching {
                if (c.length() > 0) tt.update(t.id, l.getString("projectId"), c)   // keep what you typed
                tt.complete(l.getString("projectId"), t.id)
                Repo.remove(this, t.id)
            }
            runOnUiThread {
                r.onSuccess { note = "Completed “${t.title}”"; showSearch() }
                    .onFailure { editable(true); editStatus.text = "Didn't complete: ${it.message}" }
            }
        }
    }

    private fun ask(msg: String, a: String, onA: () -> Unit, b: String, onB: () -> Unit) {
        banner.removeAllViews()
        banner.addView(text(msg, 14f))
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(button(a, 0x33FFFFFF) { banner.visibility = View.GONE; onA() })
        buttons.addView(button(b, 0x33FFFFFF) { banner.visibility = View.GONE; onB() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        banner.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        banner.visibility = View.VISIBLE
    }

    private fun back() {
        if (changes().length() > 0) ask("You have changes that aren't saved.", "Save", { save(force = false) }, "Discard", ::showSearch)
        else showSearch()
    }

    private fun showSearch() {
        editing = null; loaded = null
        banner.visibility = View.GONE; editPage.visibility = View.GONE; searchPage.visibility = View.VISIBLE
        setFinishOnTouchOutside(true)
        box.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(box, 0)
        render()
    }

    @Deprecated("Back in the editor goes to the matches first")
    override fun onBackPressed() {
        if (editing != null) back() else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) { if (editing != null) back() else finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
