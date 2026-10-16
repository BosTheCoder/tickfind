package io.github.bosthecoder.tickfind

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import kotlin.concurrent.thread

/** Settings: sign-in, the ways to open Tickfind fast, how search behaves, and updates. Rebuilt on every resume. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private var signInMsg: String? = null
    private var release: Update.Release? = null
    private var updateMsg: String? = null
    private var updating = false
    private lateinit var updateBox: LinearLayout
    private val onSync: () -> Unit = { build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(20); setPadding(p, dp(36), p, dp(36)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E0E10.toInt()); addView(list) })
        Repo.listen(onSync)
        if (savedInstanceState == null) handle(intent)   // not again when it's recreated with the same sign-in redirect
        check(quiet = true)
    }

    override fun onDestroy() { Repo.unlisten(onSync); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); build() }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "io.github.bosthecoder.tickfind" && store.pending != null) finishSignIn(data)
        if (intent?.action == Update.ACTION) installStatus(intent)
    }

    // ---------- Sign-in ----------

    private fun signIn() {
        signInMsg = "Opening TickTick…"; build()
        thread {
            val r = runCatching { TickTick(store).startSignIn() }
            runOnUiThread {
                r.onSuccess { url -> signInMsg = "Allow Tickfind in the browser, then you'll come back here."; startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { signInMsg = "Couldn't start sign-in: ${it.message}" }
                build()
            }
        }
    }

    /** TickTick sends the browser back to io.github.bosthecoder.tickfind:/oauth?code=… */
    private fun finishSignIn(back: Uri) {
        signInMsg = "Finishing sign-in…"
        thread {
            val r = runCatching { TickTick(store).finishSignIn(back) }
            runOnUiThread {
                signInMsg = r.exceptionOrNull()?.let { "Sign-in failed: ${it.message}" }
                if (r.isSuccess) Repo.sync(this)
                build()
            }
        }
    }

    // ---------- Updates (same as Glance) ----------

    private fun installStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)?.let { startActivity(it) }
            PackageInstaller.STATUS_SUCCESS -> updateMsg = "Updated."
            else -> updateMsg = "Update didn't install: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"}"
        }
        if (::updateBox.isInitialized) renderUpdate()
    }

    private fun check(quiet: Boolean) {
        if (!quiet) { updateMsg = "Checking…"; if (::updateBox.isInitialized) renderUpdate() }
        thread {
            val result = runCatching { Update.latest() }
            runOnUiThread {
                val mine = Update.version(this)
                result.onSuccess { r ->
                    when {
                        isNewer(r.tag, mine) && r.apk != null -> { release = r; updateMsg = null }
                        isNewer(r.tag, mine) -> if (!quiet) updateMsg = "${r.tag} is out, but its release has no Tickfind.apk yet"
                        else -> if (!quiet) updateMsg = "You're on the latest (v$mine)"
                    }
                }.onFailure { if (!quiet) updateMsg = "Couldn't check for updates: ${it.message ?: "no connection?"}" }
                if (::updateBox.isInitialized) renderUpdate()
            }
        }
    }

    private fun install(r: Update.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateMsg = "Android needs your OK once: switch on \"Allow from this source\" for Tickfind, then come back and tap Update again."
            renderUpdate()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updating = true; updateMsg = "Downloading ${r.tag}…"; renderUpdate()
        thread {
            val result = runCatching { Update.install(applicationContext, r) }
            runOnUiThread {
                updating = false
                updateMsg = result.fold({ "Confirm the update when Android asks." }, { "Update failed: ${it.message}" })
                renderUpdate()
            }
        }
    }

    private fun renderUpdate() {
        updateBox.removeAllViews()
        val r = release
        updateBox.addView(button(if (r != null) "Update to ${r.tag}" else "Check for updates (v${Update.version(this)})", if (r != null) 0xFF1D4D3A.toInt() else 0xFF1A1A1E.toInt()) {
            if (updating) return@button
            if (r != null) install(r) else check(quiet = false)
        })
        updateMsg?.let { updateBox.addView(text(it, 12f, DIM).apply { maxLines = 4 }) }
    }

    // ---------- The page ----------

    private fun build() {
        list.removeAllViews()
        list.addView(text("Tickfind", 28f, Color.WHITE, bold = true))
        list.addView(text("Quick search for your TickTick tasks, floating over any app.", 14f, DIM))

        header("TickTick")
        val i = Repo.index(this)
        if (store.signedIn) {
            list.addView(text(if (Repo.syncing) "Syncing…" else "Signed in · %,d tasks · synced %s".format(i.tasks.size, if (i.synced > 0) ago(i.synced) else "never"), 14f))
            Repo.error?.let { list.addView(text(it, 12f, LATE)) }
            row(button("Sync now", 0xFF1A1A1E.toInt()) { Repo.sync(this) }, button("Sign out", 0xFF3A1F1F.toInt()) {
                store.setToken(null, 0); Repo.clear(this); build()
            })
        } else list.addView(button("Sign in to TickTick", 0xFF22305A.toInt()) { signIn() })
        signInMsg?.let { list.addView(text(it, 12f, DIM)) }

        header("Open it fast")
        val bubbleOn = BubbleService.running
        toggle("Floating bubble over other apps", store.bubble && bubbleOn) { on ->
            store.bubble = on
            when {
                !on -> BubbleService.stop(this)
                !BubbleService.canOverlay(this) -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                else -> {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    BubbleService.start(this)
                }
            }
            list.postDelayed({ build() }, 400)
        }
        if (store.bubble && !bubbleOn && BubbleService.canOverlay(this)) BubbleService.start(this)   // back from the overlay permission page
        tip("Tap the bubble to search, drag it along the edge, long-press it for these settings.")
        list.addView(button("Use as the assistant app (long-press power or swipe from a corner)", 0xFF1A1A1E.toInt()) {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        })
        tip("Pick Tickfind under Default digital assistant app. Also: the Search TickTick tile in Quick Settings (swipe down twice, tap the pencil, drag it in), a Samsung side-key double press or Pixel Quick Tap set to open Tickfind, or the app icon.")

        header("Views")
        tip("Shown while the search box is empty; the first one when it opens. A view's search works like the box (syntax below), and a | b means either.")
        val views = store.views
        fun save(i: Int, change: (Store.View) -> Store.View) { store.views = store.views.toMutableList().also { it[i] = change(it[i]) } }
        for ((i, v) in views.withIndex()) {
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(field(v.name, "Name") { s -> save(i) { it.copy(name = s) } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                // Move up, move down, remove
                for ((label, move) in listOf("↑" to -1, "↓" to 1, "✕" to 0)) {
                    val can = move == 0 || (i + move) in views.indices
                    addView(text(label, 20f, if (can) DIM else 0x22F2F2F4).apply {
                        gravity = Gravity.CENTER; background = ripple(dp(20))
                        setOnClickListener {
                            if (!can) return@setOnClickListener
                            val l = store.views.toMutableList()
                            if (move == 0) l.removeAt(i) else l[i] = l[i + move].also { l[i + move] = l[i] }
                            store.views = l; build()
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                }
            })
            list.addView(field(v.query, "Search, e.g. due:today | #today") { s -> save(i) { it.copy(query = s) } },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(16) })
        }
        list.addView(button("Add view", 0xFF1A1A1E.toInt()) { store.views = store.views + Store.View("New view", ""); build() })

        header("Search")
        choice("Sort by", Sort.entries, { when (it) { Sort.Best -> "Best match"; Sort.Modified -> "Changed"; Sort.Created -> "Added"; else -> it.name } }, store.sort) { store.sort = it }
        choice("Matches shown", listOf(4, 6, 8, 10), { "$it" }, store.results) { store.results = it }
        list.addView(text("Default filter, added to every search", 14f).apply { setPadding(0, dp(10), 0, dp(4)) })
        list.addView(EditText(this).apply {
            setText(store.defaultFilter); hint = "e.g. -~gcal or !high"; setTextColor(TEXT); setHintTextColor(FAINT); isSingleLine = true
            background = rounded(FIELD, dp(10).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { _, has -> if (!has) store.defaultFilter = text.toString().trim() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { store.defaultFilter = s.toString().trim() }
            })
        })
        tip("#tag  ~list  -~list  !high !med !low !none  due:today due:week due:overdue has:due (several: any)  a | b (either)  is:note is:checklist  -word  \"exact words\"  sort:priority|best|due|modified|created")
        toggle("Include archived lists", store.archived) { store.archived = it; Repo.sync(this) }
        val names = i.tasks.map { it.list }.toSortedSet() + store.excludeLists
        if (names.isNotEmpty()) {
            list.addView(text("Lists to leave out", 14f).apply { setPadding(0, dp(10), 0, 0) })
            for (name in names.sorted()) list.addView(CheckBox(this).apply {
                text = name; setTextColor(TEXT); isChecked = name in store.excludeLists; buttonTintList = ColorStateList.valueOf(BLUE)
                setOnCheckedChangeListener { _, on -> store.excludeLists = if (on) store.excludeLists + name else store.excludeLists - name }
            })
        }

        header("Open in TickTick goes to")
        choice("", listOf(false, true), { if (it) "The browser" else "The TickTick app" }, store.web) { store.web = it }

        header("Updates")
        updateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(updateBox)
        renderUpdate()
    }

    /** A one-line text field that saves as you type. */
    private fun field(value: String, hint: String, save: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(TEXT); setHintTextColor(FAINT); isSingleLine = true; textSize = 15f
        background = rounded(FIELD, dp(10).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { save(s.toString()) }
        })
    }

    private fun header(s: String) = list.addView(text(s.uppercase(), 12f, 0x8CFFFFFF.toInt(), bold = true).apply {
        letterSpacing = 0.08f; setPadding(0, dp(24), 0, dp(6))
    })

    private fun tip(s: String) = list.addView(text(s, 12f, FAINT).apply { setPadding(0, dp(2), 0, dp(10)) })

    private fun button(s: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f; stateListAnimator = null
        background = rounded(bg, dp(12).toFloat())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = list.addView(LinearLayout(this).apply {
        for ((n, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(8) })
    })

    private fun <T> choice(label: String, options: List<T>, show: (T) -> String, current: T, pick: (T) -> Unit) {
        if (label.isNotEmpty()) list.addView(text(label, 14f).apply { setPadding(0, dp(8), 0, 0) })
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }.also { it.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for (o in options) addView(RadioButton(context).apply {
                text = show(o); setTextColor(TEXT); id = View.generateViewId(); buttonTintList = ColorStateList.valueOf(BLUE)
                layoutParams = RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
                isChecked = o == current
                setOnCheckedChangeListener { _, on -> if (on) pick(o) }
            })
        }) })
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        text = label; setTextColor(TEXT); textSize = 14f; isChecked = on; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, v -> set(v) }
    })
}
