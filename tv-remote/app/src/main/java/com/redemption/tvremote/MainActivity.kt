package com.redemption.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.redemption.tvremote.protocol.ClientIdentity
import com.redemption.tvremote.protocol.KeyCode
import com.redemption.tvremote.protocol.KeyDirection
import com.redemption.tvremote.protocol.PairingClient
import com.redemption.tvremote.protocol.RemoteClient
import com.redemption.tvremote.protocol.SonyBravia
import com.redemption.tvremote.protocol.TextSync
import java.net.URL
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Hold { NONE, REPEAT, LONG }
    private enum class Status { OK, BUSY, ERROR }

    /** What the Menu button sends. TVs differ, so the user can pick what works on theirs. */
    enum class MenuAction(val title: String, val detail: String) {
        SONY_ACTION_MENU("Sony Action Menu", "Sony Bravia Quick Settings bar (picture, sound, speakers…). Needs IP control, set up below."),
        QUICK_MENU("TV quick menu", "Holds Home. Opens the Google TV / Android TV panel with picture, sound and settings."),
        SETTINGS_KEY("Settings key", "The gear / settings key found on many TV-brand remotes (TCL, Hisense, Sony, Philips)."),
        TV_MENU_KEY("TV menu key", "The TV context-menu key some remotes have."),
        APP_OPTIONS("App options", "Opens the options menu of the app on screen (key 82)."),
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val web = Executors.newSingleThreadExecutor()
    private val appStore by lazy { AppStore(prefs) }
    private val iconCache = HashMap<String, Bitmap>()
    private var sonyClient: SonyBravia? = null
    private val prefs by lazy { getSharedPreferences("tv_remote", MODE_PRIVATE) }
    @Volatile private var identity: ClientIdentity? = null

    private var remote: RemoteClient? = null
    private var pairing: PairingClient? = null
    private var started = false
    private lateinit var discovery: TvDiscovery

    // Views
    private lateinit var root: FrameLayout
    private lateinit var devicesPanel: View
    private lateinit var remotePanel: View
    private lateinit var remoteControls: View
    private lateinit var tvName: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var deviceList: LinearLayout
    private lateinit var searchingText: TextView
    private lateinit var hostInput: EditText
    private lateinit var backToRemote: TextView
    private lateinit var navHost: FrameLayout
    private lateinit var navToggle: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var appsRow: LinearLayout
    private lateinit var settingsPanel: View
    private lateinit var settingsBody: LinearLayout
    private lateinit var keyboardBar: View
    private lateinit var keyboardInput: EditText
    private lateinit var keyboardTitle: TextView
    private lateinit var keyboardSearchRow: LinearLayout

    private val savedHost: String? get() = prefs.getString(KEY_HOST, null)
    private val savedName: String? get() = prefs.getString(KEY_NAME, null)
    private var useTrackpad: Boolean
        get() = prefs.getBoolean(KEY_TRACKPAD, true)
        set(v) { prefs.edit().putBoolean(KEY_TRACKPAD, v).apply() }
    private var menuAction: MenuAction
        get() = runCatching { MenuAction.valueOf(prefs.getString(KEY_MENU_ACTION, null)!!) }
            .getOrDefault(if (sonyPsk != null) MenuAction.SONY_ACTION_MENU else MenuAction.QUICK_MENU)
        set(v) { prefs.edit().putString(KEY_MENU_ACTION, v.name).apply() }

    private val sonyPsk: String? get() = prefs.getString(KEY_SONY_PSK, null)?.takeIf { it.isNotEmpty() }

    /** Sony IP-control client for the current TV, if a Pre-Shared Key has been set. */
    private fun sony(): SonyBravia? {
        // Sony IP control needs the TV's IPv4 address; fall back to the one it was set up with.
        val host = savedHost?.takeIf { isIpv4(it) } ?: prefs.getString(KEY_SONY_HOST, null) ?: savedHost ?: return null
        val psk = sonyPsk ?: return null
        sonyClient?.let { if (it.host == host && it.psk == psk) return it }
        return SonyBravia(host, psk).also { sonyClient = it }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = TvDiscovery(this) { showDevices(it) }
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        devicesPanel = buildDevicesPanel()
        remotePanel = buildRemotePanel()
        settingsPanel = buildSettingsPanel().apply { visibility = View.GONE }
        root.addView(devicesPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(remotePanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(settingsPanel, FrameLayout.LayoutParams(-1, -1))
        keyboardBar = buildKeyboardBar().apply { visibility = View.GONE }
        root.addView(keyboardBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        if (savedHost != null) showRemotePanel() else showDevicesPanel()
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Version 1.4 could save an IPv6 address for the TV, which broke Sony control.
        // Look for the TV by name so we can switch back to its IPv4 address.
        if (savedHost?.let { !isIpv4(it) } == true && remotePanel.visibility == View.VISIBLE) discovery.start()
        if (remotePanel.visibility == View.VISIBLE) savedHost?.let { connect(it) } else discovery.start()
    }

    override fun onStop() {
        super.onStop()
        started = false
        discovery.stop()
        disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        web.shutdownNow()
    }

    /** Back steps out of Settings / the TV list before leaving the app. */
    private fun handleBack() {
        when {
            keyboardBar.visibility == View.VISIBLE -> hideKeyboardBar()
            settingsPanel.visibility == View.VISIBLE -> closeSettings()
            devicesPanel.visibility == View.VISIBLE && savedHost != null -> { showRemotePanel(); savedHost?.let { connect(it) } }
            else -> finish()
        }
    }

    // Android 12 and older. Android 13+ uses the OnBackInvokedCallback registered in onCreate.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = handleBack()

    /** The phone's own volume buttons control the TV while the remote is showing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val r = remote
        if (remotePanel.visibility == View.VISIBLE && r != null && r.isReady) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { r.sendKey(KeyCode.VOLUME_UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { r.sendKey(KeyCode.VOLUME_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- connection

    private fun withIdentity(block: (ClientIdentity) -> Unit) {
        io.execute {
            val id = identity ?: ClientIdentity.loadOrCreate(File(filesDir, "client.p12"), "tvremote")
                .also { identity = it }
            block(id)
        }
    }

    private fun isPaired(host: String) = prefs.getStringSet(KEY_PAIRED, emptySet())!!.contains(host)

    private fun pinKey(host: String) = "pin_$host"

    private fun setPaired(host: String, paired: Boolean) {
        // A new pairing (or losing one) means the TV's certificate is learned again.
        prefs.edit().remove(pinKey(host)).apply()
        val set = prefs.getStringSet(KEY_PAIRED, emptySet())!!.toMutableSet()
        if (paired) set.add(host) else set.remove(host)
        prefs.edit().putStringSet(KEY_PAIRED, set).apply()
    }

    private fun moveTv(old: String, new: String) {
        val paired = isPaired(old)
        val pin = prefs.getString(pinKey(old), null)
        prefs.edit().putString(KEY_HOST, new).apply()
        if (paired) {
            setPaired(new, true)
            if (pin != null) prefs.edit().putString(pinKey(new), pin).apply()
        }
        sonyClient = null
        if (prefs.contains(KEY_SONY_HOST)) prefs.edit().putString(KEY_SONY_HOST, new).apply()
        discovery.stop()
        connect(new)
    }

    private fun selectTv(host: String, name: String) {
        prefs.edit().putString(KEY_HOST, host).putString(KEY_NAME, name).apply()
        discovery.stop()
        showRemotePanel()
        connect(host)
    }

    private fun connect(host: String) {
        disconnect()
        if (isPaired(host)) openRemote(host) else startPairing(host)
    }

    private fun disconnect() {
        lastField = null
        if (::keyboardBar.isInitialized && keyboardBar.visibility == View.VISIBLE) hideKeyboardBar()
        remote?.close(); remote = null
        pairing?.close(); pairing = null
        main.removeCallbacksAndMessages(RECONNECT)
        setReady(false)
    }

    private fun openRemote(host: String) {
        setStatus("Connecting…", Status.BUSY)
        withIdentity { id ->
            main.post {
                if (!started || savedHost != host) return@post
                lateinit var client: RemoteClient
                val pin = prefs.getString(pinKey(host), null)
                client = RemoteClient(host, id, object : RemoteClient.Listener {
                    override fun onReady() {
                        main.post {
                            if (remote !== client) return@post
                            // First connection after pairing: remember the TV's certificate.
                            if (pin == null) client.serverPin?.let { prefs.edit().putString(pinKey(host), it).apply() }
                            // Keep looking only if we're on a non-IPv4 address (see healAddress).
                            if (isIpv4(host)) discovery.stop() else discovery.start()
                            setReady(true); setStatus("Connected", Status.OK)
                        }
                    }

                    override fun onDeviceInfo(model: String, vendor: String) {
                        if (vendor.contains("sony", true) || model.contains("bravia", true)) {
                            main.post { if (remote === client) suggestSonySetup() }
                        }
                    }

                    override fun onTextField(field: RemoteClient.TextField?, opened: Boolean) {
                        main.post { if (remote === client) onTvTextField(field, opened) }
                    }

                    override fun onVolume(level: Int, max: Int, muted: Boolean) {
                        main.post { if (remote === client) showVolume(level, max, muted) }
                    }

                    override fun onDisconnected(error: Throwable?, beforeReady: Boolean) {
                        main.post { if (remote === client) onRemoteLost(host, error, beforeReady) }
                    }
                }, deviceModel = Build.MODEL ?: "Phone", deviceVendor = Build.MANUFACTURER ?: "Android", expectedPin = pin)
                remote = client
                client.connect()
            }
        }
    }

    private fun onRemoteLost(host: String, error: Throwable?, beforeReady: Boolean) {
        remote = null
        setReady(false)
        if (!started) return
        when {
            error is ConnectException || error is SocketTimeoutException ||
                error is NoRouteToHostException || error is UnknownHostException ->
            {
                setStatus("Can't reach the TV. Retrying…", Status.ERROR, retry = true)
                // The TV may be off, or its address may have changed: look for it by name.
                discovery.start()
                main.postAtTime({ if (started && savedHost == host && remote == null) openRemote(host) }, RECONNECT,
                    SystemClock.uptimeMillis() + 10_000)
            }
            beforeReady -> {
                // The TV refused us, or its certificate no longer matches: pair again.
                setPaired(host, false)
                startPairing(host)
            }
            else -> {
                setStatus("Connection lost. Reconnecting…", Status.BUSY)
                main.postAtTime({ if (started && savedHost == host) openRemote(host) }, RECONNECT,
                    SystemClock.uptimeMillis() + 2000)
            }
        }
    }

    private fun startPairing(host: String) {
        setStatus("Pairing… look at your TV for a code", Status.BUSY)
        withIdentity { id ->
            val client = PairingClient(host, id)
            main.post { pairing = client }
            try {
                client.start(clientName = "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                main.post { if (pairing === client) askForCode(host, client, null) }
            } catch (e: Exception) {
                client.close()
                main.post {
                    if (pairing === client) {
                        pairing = null
                        setStatus("Pairing failed: ${e.message ?: e.javaClass.simpleName}", Status.ERROR, retry = true)
                    }
                }
            }
        }
    }

    private fun askForCode(host: String, client: PairingClient, error: String?) {
        if (isFinishing) return
        val input = EditText(this).apply {
            hint = "6-character code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            typeface = Typeface.MONOSPACE
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Enter the code shown on your TV")
            .setMessage(error)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                if (!client.isCodeValid(code)) {
                    askForCode(host, client, "That code doesn't match. Check the TV and try again.")
                    return@setPositiveButton
                }
                setStatus("Pairing…", Status.BUSY)
                io.execute {
                    try {
                        client.finish(code)
                        main.post {
                            pairing = null
                            setPaired(host, true)
                            if (started && savedHost == host) openRemote(host)
                        }
                    } catch (e: Exception) {
                        client.close()
                        main.post {
                            pairing = null
                            setStatus("Pairing failed: ${e.message ?: e.javaClass.simpleName}", Status.ERROR, retry = true)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                client.close(); pairing = null
                setStatus("Pairing cancelled", Status.ERROR, retry = true)
            }
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- sending

    private fun send(code: Int, direction: KeyDirection = KeyDirection.SHORT) {
        val r = remote?.takeIf { it.isReady }
        if (r != null) { r.sendKey(code, direction); return }
        if (code == KeyCode.POWER) powerOnViaSony()
    }

    /** A TV in standby can't take remote keys; a Bravia with IP control can still be woken. */
    private fun powerOnViaSony() {
        val tv = sony() ?: return toast("TV is off or unreachable")
        toast("Turning the TV on…")
        web.execute {
            try {
                tv.powerOn()
                main.postDelayed({ savedHost?.let { if (started && remote == null) connect(it) } }, 6000)
            } catch (e: Exception) {
                main.post { toast("Couldn't wake the TV: ${e.message}") }
            }
        }
    }

    private fun pressMenu() {
        when (menuAction) {
            MenuAction.SONY_ACTION_MENU -> {
                val tv = sony()
                if (tv == null) {
                    toast("Set up Sony IP control in ⚙ first")
                    openSettings(atSony = true)
                    return
                }
                web.execute {
                    try {
                        tv.pressButton(*SonyBravia.ACTION_MENU_NAMES)
                    } catch (e: Exception) {
                        main.post { toast("Sony: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
            MenuAction.QUICK_MENU -> {
                // A real long-press of Home: key down, held, then released.
                send(KeyCode.HOME, KeyDirection.START_LONG)
                main.postDelayed({ send(KeyCode.HOME, KeyDirection.END_LONG) }, 900)
            }
            MenuAction.SETTINGS_KEY -> send(KeyCode.SETTINGS)
            MenuAction.TV_MENU_KEY -> send(KeyCode.TV_CONTEXT_MENU)
            MenuAction.APP_OPTIONS -> send(KeyCode.MENU)
        }
        if (!prefs.getBoolean(KEY_MENU_HINT_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_MENU_HINT_SHOWN, true).apply()
            Toast.makeText(this, "Wrong menu? Tap ⚙ at the top to choose what Menu opens.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- live keyboard

    private val textSync = TextSync(object : TextSync.Output {
        override val structured get() = remote?.hasTextField == true
        override val reportsEdits get() = remote?.reportsEdits == true
        override fun fieldGone() {
            lastField = null
            remote?.forgetTextField()
            if (keyboardBar.visibility == View.VISIBLE) setKeyboardMode(KEYS_TITLE, keys = true)
        }
        override fun insert(text: String) { remote?.insertText(text) }
        override fun deleteBack(count: Int) { repeat(count) { send(KeyCode.DEL) } }
        override fun typeKeys(text: String) { remote?.typeKeys(text) }
    })
    private var keyboardAuto = false
    private var settingTextFromTv = false
    private val syncTimeout = object : Runnable {
        override fun run() { if (textSync.onTimeout()) main.postDelayed(this, SYNC_TIMEOUT_MS) }
    }

    private fun afterSync(waiting: Boolean) {
        main.removeCallbacks(syncTimeout)
        if (waiting) main.postDelayed(syncTimeout, SYNC_TIMEOUT_MS)
    }

    /** The TV's currently open text box, as last reported (null when none is known). */
    private var lastField: RemoteClient.TextField? = null

    private fun onTvTextField(field: RemoteClient.TextField?, opened: Boolean) {
        if (opened || field != null) lastField = field
        if (field == null) {
            // The TV moved off a shared text field. Only auto-close what auto-opened.
            if (opened && keyboardAuto && keyboardBar.visibility == View.VISIBLE) hideKeyboardBar()
            return
        }
        if (opened || keyboardBar.visibility != View.VISIBLE) {
            showKeyboardBar(field.value, field.label, auto = true)
        } else {
            afterSync(textSync.onTvValue(field.value))
        }
    }

    private fun showKeyboardBar(tvValue: String, label: String = "", auto: Boolean) {
        if (remotePanel.visibility != View.VISIBLE) return
        val wasOpen = keyboardBar.visibility == View.VISIBLE
        keyboardAuto = auto && (!wasOpen || keyboardAuto)
        textSync.reset(tvValue)
        settingTextFromTv = true
        keyboardInput.setText(tvValue)
        keyboardInput.setSelection(tvValue.length)
        settingTextFromTv = false
        when {
            label.isNotBlank() -> setKeyboardMode("Typing on TV · $label", keys = false)
            remote?.hasTextField == true -> setKeyboardMode("Typing on TV", keys = false)
            else -> setKeyboardMode(KEYS_TITLE, keys = true)
        }
        keyboardBar.visibility = View.VISIBLE
        keyboardInput.requestFocus()
        keyboardInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setKeyboardMode(title: String, keys: Boolean) {
        keyboardTitle.text = title
        keyboardSearchRow.visibility = if (keys) View.VISIBLE else View.GONE
    }

    /**
     * Opens the TV's Google / Android TV search with [query] filled in, as an intent link sent
     * through the remote protocol. Results include titles from Netflix, Prime Video and others.
     */
    private fun searchOnTv(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return toast("Type what to search for first")
        val r = remote?.takeIf { it.isReady } ?: return toast("Not connected to the TV")
        r.openLink("intent:#Intent;action=android.search.action.GLOBAL_SEARCH;S.query=${Uri.encode(q)};end")
        hideKeyboardBar()
    }

    private fun hideKeyboardBar() {
        main.removeCallbacks(syncTimeout)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(keyboardInput.windowToken, 0)
        keyboardInput.clearFocus()
        keyboardBar.visibility = View.GONE
    }

    private fun buildKeyboardBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(12))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
                setColor(color(R.color.surface_hi))
            }
            elevation = dp(8).toFloat()
            isClickable = true
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(R.drawable.ic_keyboard, R.color.accent), LinearLayout.LayoutParams(dp(18), dp(18)))
        keyboardTitle = text("Typing on TV", 13f, R.color.muted, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(keyboardTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        top.addView(iconButton(R.drawable.ic_close, "Close keyboard", 36, bg = null).apply { setOnClickListener { hideKeyboardBar() } },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        bar.addView(top, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        keyboardInput = EditText(this).apply {
            hint = "Type here, it shows on the TV live"
            setTextColor(color(R.color.text)); setHintTextColor(color(R.color.muted))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = rounded(R.color.surface, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 1
            setOnEditorActionListener { _, _, _ ->
                send(KeyCode.ENTER)
                hideKeyboardBar()
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!settingTextFromTv) afterSync(textSync.onUserText(s?.toString() ?: ""))
                }
            })
        }
        row.addView(keyboardInput, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Enter", null, R.color.accent_container, R.color.accent) {
            send(KeyCode.ENTER); hideKeyboardBar()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Apps like Netflix and Prime Video draw their own keyboard and ignore remote typing.
        // Offer the TV's own search instead; it finds titles in those apps too.
        keyboardSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        keyboardSearchRow.addView(text("App not taking your typing? (Netflix, Prime Video)", 12f, R.color.muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        keyboardSearchRow.addView(chip("Search on TV", null, R.color.surface, R.color.text, small = true) {
            searchOnTv(keyboardInput.text.toString())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        bar.addView(keyboardSearchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return bar
    }

    // ---------------------------------------------------------------- UI state

    private fun showRemotePanel() {
        devicesPanel.visibility = View.GONE
        remotePanel.visibility = View.VISIBLE
        tvName.text = savedName ?: savedHost ?: "TV"
    }

    private fun showDevicesPanel() {
        disconnect()
        remotePanel.visibility = View.GONE
        devicesPanel.visibility = View.VISIBLE
        backToRemote.visibility = if (savedHost != null) View.VISIBLE else View.GONE
        if (started) discovery.start()
    }

    private fun setReady(ready: Boolean) {
        if (!::remoteControls.isInitialized) return
        remoteControls.alpha = if (ready) 1f else 0.5f
        if (!ready) { volumeLabel.text = "VOL"; setMuted(false) }
    }

    private fun showVolume(level: Int, max: Int, muted: Boolean) {
        volumeLabel.text = if (muted) "MUTE" else if (max == 100 || max == 0) "$level" else "$level/$max"
        setMuted(muted)
    }

    private fun setMuted(muted: Boolean) {
        muteButton.background = circle(if (muted) R.color.accent_container else R.color.surface)
        muteButton.imageTintList = ColorStateList.valueOf(color(if (muted) R.color.accent else R.color.text))
    }

    private fun setStatus(text: String, status: Status, retry: Boolean = false) {
        statusText.text = if (retry) "$text · Tap to retry" else text
        statusText.setTextColor(color(if (status == Status.ERROR) R.color.danger else R.color.muted))
        (statusDot.background as GradientDrawable).setColor(color(when (status) {
            Status.OK -> R.color.ok
            Status.BUSY -> R.color.warn
            Status.ERROR -> R.color.danger
        }))
        statusText.setOnClickListener(if (retry) View.OnClickListener { savedHost?.let { connect(it) } } else null)
        statusText.isClickable = retry
    }

    private fun showDevices(list: List<TvDevice>) {
        if (remotePanel.visibility == View.VISIBLE) {
            // Reconnecting: the saved TV turned up at a new address (routers reassign them).
            val moved = list.firstOrNull { it.name == savedName && it.host != savedHost && isIpv4(it.host) } ?: return
            moveTv(savedHost ?: return, moved.host)
            return
        }
        deviceList.removeAllViews()
        searchingText.text = if (list.isEmpty()) "Searching for TVs on your network…" else "TVs found"
        list.forEach { tv ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(R.color.surface, 18)
                setOnClickListener { selectTv(tv.host, tv.name) }
            }
            row.addView(icon(R.drawable.ic_tv, R.color.accent), LinearLayout.LayoutParams(dp(26), dp(26)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(tv.name, 16f, R.color.text, bold = true), wrap())
                addView(text(tv.host, 13f, R.color.muted), wrap())
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            deviceList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    // ---------------------------------------------------------------- devices panel

    private fun buildDevicesPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        col.addView(text("Choose your TV", 26f, R.color.text, bold = true), wrap())
        col.addView(text("Your phone and the TV need to be on the same Wi-Fi network.", 14f, R.color.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(24) })
        searchingText = text("Searching for TVs on your network…", 13f, R.color.muted)
        col.addView(searchingText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(deviceList, LinearLayout.LayoutParams(-1, -2))

        col.addView(text("Or enter the TV's IP address", 15f, R.color.text, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        col.addView(text("On the TV: Settings → Network & Internet → your network.", 13f, R.color.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hostInput = EditText(this).apply {
            hint = "192.168.1.50"
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.muted))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(R.color.surface, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnEditorActionListener { _, _, _ -> connectManual(); true }
        }
        row.addView(hostInput, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Connect", null, R.color.accent_container, R.color.accent) { connectManual() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))

        backToRemote = chip("Back to remote", R.drawable.ic_back) { showRemotePanel(); savedHost?.let { connect(it) } }
        col.addView(backToRemote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        col.addView(chip("Forget paired TVs", null) {
            prefs.edit().remove(KEY_PAIRED).remove(KEY_HOST).remove(KEY_NAME).apply()
            backToRemote.visibility = View.GONE
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        return ScrollView(this).apply { addView(col, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun connectManual() {
        val host = hostInput.text.toString().trim()
        if (host.isNotEmpty()) selectTv(host, host)
    }

    // ---------------------------------------------------------------- remote panel

    private fun buildRemotePanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }

        // Header: [ TV name · status ]  [settings] [power]
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val device = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(R.color.surface, 24)
            contentDescription = "Choose TV"
            setOnClickListener { showDevicesPanel() }
        }
        device.addView(icon(R.drawable.ic_tv, R.color.text), LinearLayout.LayoutParams(dp(22), dp(22)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvName = text("TV", 16f, R.color.text, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.warn)) } }
        statusText = text("Not connected", 12f, R.color.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        titles.addView(tvName, LinearLayout.LayoutParams(-1, -2))
        titles.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
        device.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        header.addView(device, LinearLayout.LayoutParams(0, dp(60), 1f))
        header.addView(iconButton(R.drawable.ic_settings, "Remote settings", 48).apply {
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        header.addView(key(R.drawable.ic_power, "Power", KeyCode.POWER, Hold.NONE, 48,
            bg = R.color.danger_container, tint = R.color.danger),
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        // App shortcuts
        appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(appsRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        renderApps()

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remoteControls = controls

        // Media bar
        val media = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(R.color.surface, 28)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun mediaKey(icon: Int, desc: String, code: Int, hold: Hold, accent: Boolean = false) =
            media.addView(FrameLayout(this).apply {
                addView(key(icon, desc, code, hold, 44,
                    bg = if (accent) R.color.accent_container else null,
                    tint = if (accent) R.color.accent else R.color.text),
                    FrameLayout.LayoutParams(dp(if (accent) 64 else 56), dp(44), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        mediaKey(R.drawable.ic_rew, "Rewind", KeyCode.MEDIA_REWIND, Hold.REPEAT)
        mediaKey(R.drawable.ic_play_pause, "Play or pause", KeyCode.MEDIA_PLAY_PAUSE, Hold.NONE, accent = true)
        mediaKey(R.drawable.ic_ff, "Fast forward", KeyCode.MEDIA_FAST_FORWARD, Hold.REPEAT)
        controls.addView(media, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Navigation: trackpad or D-pad
        // The navigation area takes whatever height is left, so tall phones get a bigger trackpad.
        navHost = FrameLayout(this).apply { minimumHeight = dp(NAV_HEIGHT) }
        controls.addView(navHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        navToggle = chip("", R.drawable.ic_swap, R.color.surface_hi, R.color.muted, small = true) {
            useTrackpad = !useTrackpad; showNavigation()
        }

        // VOL rocker · mute / input / keyboard · CH rocker
        volumeLabel = text("VOL", 12f, R.color.muted, bold = true).apply { gravity = Gravity.CENTER }
        muteButton = key(R.drawable.ic_mute, "Mute", KeyCode.MUTE, Hold.NONE, 46)
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(muteButton, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(key(R.drawable.ic_input, "Input source", KeyCode.TV_INPUT, Hold.NONE, 46),
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(9) })
            addView(iconButton(R.drawable.ic_keyboard, "Keyboard", 46).apply { setOnClickListener {
                if (keyboardBar.visibility == View.VISIBLE) hideKeyboardBar() else {
                    val field = lastField
                    // No text box reported (Netflix, Prime Video…): type key by key.
                    if (field == null) remote?.forgetTextField()
                    showKeyboardBar(field?.value ?: "", field?.label ?: "", auto = false)
                }
            } },
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(9) })
        }
        controls.addView(evenRow(
            rocker(R.drawable.ic_plus, "Volume up", KeyCode.VOLUME_UP, R.drawable.ic_minus, "Volume down", KeyCode.VOLUME_DOWN, volumeLabel),
            middle,
            rocker(R.drawable.ic_up, "Channel up", KeyCode.CHANNEL_UP, R.drawable.ic_down, "Channel down", KeyCode.CHANNEL_DOWN,
                text("CH", 12f, R.color.muted, bold = true).apply { gravity = Gravity.CENTER }),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        // Home · Back · Menu
        val menuButton = iconButton(R.drawable.ic_menu, "Menu", 56).apply {
            bindTap(this) { pressMenu() }
        }
        controls.addView(evenRow(
            labeled(key(R.drawable.ic_home, "Home", KeyCode.HOME, Hold.LONG, 56), "Home"),
            labeled(key(R.drawable.ic_back, "Back", KeyCode.BACK, Hold.LONG, 56), "Back"),
            labeled(menuButton, "Menu"),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(controls, LinearLayout.LayoutParams(-1, 0, 1f))
        showNavigation()
        setReady(false)
        return ScrollView(this).apply { isFillViewport = true; addView(col, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun showNavigation() {
        navHost.removeAllViews()
        if (useTrackpad) {
            val pad = TrackpadView(this, TrackpadView.Colors(
                surface = color(R.color.surface),
                dots = color(R.color.outline),
                glow = (color(R.color.accent) and 0x00FFFFFF) or 0x40000000,
                hint = color(R.color.muted),
                edge = color(R.color.accent),
            )) { code, dir -> send(code, dir) }
            navHost.addView(pad, FrameLayout.LayoutParams(-1, -1))
        } else {
            navHost.addView(buildDpad(), FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        navToggle.text = if (useTrackpad) "D-pad" else "Trackpad"
        navHost.addView(navToggle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(10); marginEnd = dp(10)
        })
    }

    private fun buildDpad(): View {
        // Square that grows with the available space (up to 300dp).
        val pad = object : FrameLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val size = minOf(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec), dp(300))
                val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
                super.onMeasure(spec, spec)
            }
        }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.surface))
                setStroke(dp(1), color(R.color.outline))
            }
        }
        val arrow = 84
        fun arrowKey(icon: Int, desc: String, code: Int, gravity: Int) =
            pad.addView(key(icon, desc, code, Hold.REPEAT, arrow, bg = null).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(26), dp(26), dp(26), dp(26))
            },
                FrameLayout.LayoutParams(dp(arrow), dp(arrow), gravity))
        arrowKey(R.drawable.ic_up, "Up", KeyCode.DPAD_UP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        arrowKey(R.drawable.ic_down, "Down", KeyCode.DPAD_DOWN, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        arrowKey(R.drawable.ic_left, "Left", KeyCode.DPAD_LEFT, Gravity.START or Gravity.CENTER_VERTICAL)
        arrowKey(R.drawable.ic_right, "Right", KeyCode.DPAD_RIGHT, Gravity.END or Gravity.CENTER_VERTICAL)
        val ok = TextView(this).apply {
            text = "OK"
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.accent))
            contentDescription = "OK"
            background = circle(R.color.accent_container)
        }
        bindKey(ok, KeyCode.DPAD_CENTER, Hold.LONG)
        pad.addView(ok, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        return pad
    }

    /** Vertical pill with + on top, a label in the middle and − below, like a physical rocker. */
    private fun rocker(
        upIcon: Int, upDesc: String, upCode: Int,
        downIcon: Int, downDesc: String, downCode: Int,
        label: TextView,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(R.color.surface, 32)
        addView(key(upIcon, upDesc, upCode, Hold.REPEAT, 64, bg = null), LinearLayout.LayoutParams(dp(64), dp(60)))
        addView(label, LinearLayout.LayoutParams(-1, dp(36)))
        addView(key(downIcon, downDesc, downCode, Hold.REPEAT, 64, bg = null), LinearLayout.LayoutParams(dp(64), dp(60)))
    }

    // ---------------------------------------------------------------- apps

    private fun renderApps() {
        appsRow.removeAllViews()
        appStore.load().forEachIndexed { i, app ->
            appsRow.addView(appTile(app), LinearLayout.LayoutParams(dp(68), -2).apply { if (i > 0) marginStart = dp(6) })
        }
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = "Add app"
            addView(FrameLayout(context).apply {
                background = rounded(R.color.surface, 16)
                addView(icon(R.drawable.ic_plus, R.color.muted), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(text("Add", 11f, R.color.muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            setOnClickListener { showAddApp() }
        }
        appsRow.addView(add, LinearLayout.LayoutParams(dp(68), -2).apply { marginStart = dp(6) })
    }

    private fun appTile(app: AppShortcut) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        contentDescription = "Open ${app.name}"
        addView(appBadge(app, 52), LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text(app.name, 11f, R.color.text).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); launchApp(app) }
        setOnLongClickListener { editApp(app); true }
    }

    /** Rounded square: the TV's own icon when known (Sony), otherwise colored initials. */
    private fun appBadge(app: AppShortcut, sizeDp: Int): View {
        val box = FrameLayout(this)
        val radius = dp(sizeDp * 16 / 52).toFloat()
        box.background = ripple(GradientDrawable().apply { cornerRadius = radius; setColor(app.color) })
        box.clipToOutline = true
        val label = TextView(this).apply {
            text = app.initials
            textSize = sizeDp * 0.34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        box.addView(label, FrameLayout.LayoutParams(-1, -1))
        if (app.iconUrl.isNotEmpty()) {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            loadIcon(app.iconUrl) { bmp -> img.setImageBitmap(bmp); label.visibility = View.GONE }
        }
        return box
    }

    private fun loadIcon(url: String, onLoaded: (Bitmap) -> Unit) {
        iconCache[url]?.let { onLoaded(it); return }
        val file = File(cacheDir, "icon_" + Integer.toHexString(url.hashCode()))
        web.execute {
            val bmp = runCatching {
                if (!file.exists()) URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                BitmapFactory.decodeFile(file.path)
            }.getOrNull() ?: return@execute
            main.post { iconCache[url] = bmp; onLoaded(bmp) }
        }
    }

    private fun launchApp(app: AppShortcut) {
        val kind = app.target.substringBefore(':')
        val value = app.target.substringAfter(':')
        if (kind == "sony") {
            val tv = sony() ?: return toast("Set up Sony IP control in ⚙ to open ${app.name}")
            web.execute {
                try { tv.launch(value) } catch (e: Exception) { main.post { toast("Couldn't open ${app.name}: ${e.message}") } }
            }
            return
        }
        val r = remote?.takeIf { it.isReady } ?: return toast("Not connected to the TV")
        r.openLink(if (kind == "pkg") "market://launch?id=$value" else value)
    }

    private fun editApp(app: AppShortcut) {
        val list = appStore.load().toMutableList()
        val i = list.indexOf(app).takeIf { it >= 0 } ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.name)
            .setItems(arrayOf("Move left", "Move right", "Remove")) { _, which ->
                when (which) {
                    0 -> if (i > 0) list.add(i - 1, list.removeAt(i))
                    1 -> if (i < list.lastIndex) list.add(i + 1, list.removeAt(i))
                    2 -> list.removeAt(i)
                }
                appStore.save(list); renderApps()
                if (settingsPanel.visibility == View.VISIBLE) renderSettings()
            }
            .show()
    }

    private fun showAddApp() {
        val current = appStore.load()
        val presets = AppPresets.all.filter { p -> current.none { it.target == p.target } }
        val labels = presets.map { it.name } + listOf("Custom app…") +
            (if (sonyPsk != null) listOf("Import apps from Sony TV…") else emptyList())
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add an app")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < presets.size -> { appStore.save(current + presets[which]); afterAppsChanged() }
                    which == presets.size -> showCustomApp()
                    else -> importSonyApps()
                }
            }
            .show()
    }

    private fun showCustomApp() {
        val name = EditText(this).apply { hint = "Name, e.g. Disney+" }
        val target = EditText(this).apply {
            hint = "Package (com.example.tv) or link (https://…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(name); addView(target)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Custom app")
            .setMessage("Find an app's package name in its Google Play web address: play.google.com/…?id=PACKAGE")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val t = AppShortcut.targetFor(target.text.toString())
                if (n.isEmpty() || t == null) return@setPositiveButton toast("Enter a name and a package name or link")
                appStore.save(appStore.load() + AppShortcut(n, t, AppPresets.colorFor(n)))
                afterAppsChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importSonyApps() {
        val tv = sony() ?: return toast("Set up Sony IP control first")
        toast("Getting the app list from your TV…")
        web.execute {
            val apps = try { tv.apps() } catch (e: Exception) {
                main.post { toast("Sony: ${e.message}") }; return@execute
            }
            main.post {
                val current = appStore.load()
                val checked = BooleanArray(apps.size) { i -> current.any { it.target == "sony:${apps[i].uri}" } }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Apps on your TV")
                    .setMultiChoiceItems(apps.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("Save") { _, _ ->
                        val kept = current.filter { !it.target.startsWith("sony:") }
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }.map {
                            AppShortcut(it.title, "sony:${it.uri}", AppPresets.colorFor(it.title), it.icon)
                        }
                        appStore.save(chosen + kept.filter { k -> chosen.none { it.name.equals(k.name, true) } })
                        afterAppsChanged()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun afterAppsChanged() {
        renderApps()
        if (settingsPanel.visibility == View.VISIBLE) renderSettings()
    }

    // ---------------------------------------------------------------- Sony

    private fun suggestSonySetup() {
        if (sonyPsk != null || prefs.getBoolean(KEY_SONY_ASKED, false) || isFinishing) return
        prefs.edit().putBoolean(KEY_SONY_ASKED, true).apply()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sony Bravia detected")
            .setMessage("Turn on Sony IP control so the Menu button opens your TV's Quick Settings " +
                "(Action Menu) and the app can open your TV's apps. It takes about a minute.")
            .setPositiveButton("Set up") { _, _ -> openSettings(atSony = true) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun testSony(psk: String, status: TextView) {
        val host = savedHost?.takeIf { isIpv4(it) } ?: prefs.getString(KEY_SONY_HOST, null) ?: run {
            status.setTextColor(color(R.color.danger))
            status.text = "Choose your TV again from the TV list (tap the TV name at the top)."
            return
        }
        status.setTextColor(color(R.color.muted))
        status.text = "Checking…"
        web.execute {
            val result = try {
                val tv = SonyBravia(host, psk)
                if (!tv.isBravia()) throw SonyBravia.SonyException("No Sony Bravia answered at $host. Is IP control on?")
                tv.remoteCodes()
                tv.apps() // needs a correct Pre-Shared Key
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (result == null) {
                    prefs.edit().putString(KEY_SONY_PSK, psk).putString(KEY_MENU_ACTION, MenuAction.SONY_ACTION_MENU.name)
                        .apply { if (isIpv4(host)) putString(KEY_SONY_HOST, host) }.apply()
                    sonyClient = null
                    toast("Sony IP control works. Menu now opens the Action Menu.")
                    renderSettings()
                } else {
                    status.setTextColor(color(R.color.danger))
                    status.text = result
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings screen

    private var sonySection: View? = null

    private fun openSettings(atSony: Boolean = false) {
        renderSettings()
        remotePanel.visibility = View.GONE
        devicesPanel.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        val scroll = settingsPanel as ScrollView
        scroll.scrollTo(0, 0)
        if (atSony) scroll.post { sonySection?.let { scroll.smoothScrollTo(0, it.top) } }
    }

    private fun closeSettings() {
        settingsPanel.visibility = View.GONE
        remotePanel.visibility = View.VISIBLE
        renderApps()
        showNavigation()
    }

    private fun buildSettingsPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_back, "Back", 48, bg = null).apply { setOnClickListener { closeSettings() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text("Settings", 22f, R.color.text, bold = true),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(header, LinearLayout.LayoutParams(-1, -2))
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(settingsBody, LinearLayout.LayoutParams(-1, -2))
        return ScrollView(this).apply { addView(col, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun renderSettings() {
        val body = settingsBody
        body.removeAllViews()

        section(body, "Menu button") { card ->
            MenuAction.entries.forEach { a ->
                card.addView(optionRow(a.title, a.detail, a == menuAction) {
                    menuAction = a
                    if (a == MenuAction.SONY_ACTION_MENU && sonyPsk == null) toast("Set up Sony IP control below to use this")
                    renderSettings()
                })
            }
        }

        sonySection = section(body, "Sony Bravia · IP control") { card ->
            val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)) }
            val ok = sonyPsk != null
            pad.addView(text(if (ok) "● Set up. Menu can open the Action Menu, and you can import your TV's apps."
                else "Needed for the Sony Action Menu (Quick Settings) and for opening the exact apps on your TV.",
                14f, if (ok) R.color.ok else R.color.text), LinearLayout.LayoutParams(-1, -2))
            pad.addView(text("On the TV:\n" +
                "1. Settings → Network & Internet → Home network → IP control\n" +
                "2. Authentication → Normal and Pre-Shared Key\n" +
                "3. Pre-Shared Key → pick a code, e.g. 1234\n" +
                "4. Enter the same code here and tap Save.", 13f, R.color.muted).apply { setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val psk = EditText(this).apply {
                hint = "Pre-Shared Key"
                setText(sonyPsk ?: "")
                setTextColor(color(R.color.text)); setHintTextColor(color(R.color.muted))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = rounded(R.color.surface_hi, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val status = text("", 13f, R.color.muted)
            row.addView(psk, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(chip("Save", null, R.color.accent_container, R.color.accent) {
                val v = psk.text.toString().trim()
                if (v.isEmpty()) {
                    prefs.edit().remove(KEY_SONY_PSK).apply(); sonyClient = null; renderSettings()
                } else testSony(v, status)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            pad.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            pad.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (ok) pad.addView(chip("Import apps from TV", R.drawable.ic_tv, R.color.surface_hi) { importSonyApps() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            card.addView(pad)
        }

        section(body, "Navigation") { card ->
            card.addView(optionRow("Trackpad", "Swipe to move, tap for OK, hold for long-press OK.", useTrackpad) {
                useTrackpad = true; renderSettings()
            })
            card.addView(optionRow("D-pad", "Arrow buttons with OK in the middle.", !useTrackpad) {
                useTrackpad = false; renderSettings()
            })
        }

        section(body, "Apps on the remote") { card ->
            val apps = appStore.load()
            if (apps.isEmpty()) card.addView(text("No apps yet.", 14f, R.color.muted).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) })
            apps.forEach { app ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(8), dp(10))
                    background = ripple(null)
                    setOnClickListener { editApp(app) }
                }
                row.addView(appBadge(app, 36), LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(text(app.name, 15f, R.color.text), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                row.addView(text("Edit", 13f, R.color.accent).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
                card.addView(row)
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(6), dp(12), dp(14)) }
            actions.addView(chip("Add app", R.drawable.ic_plus, R.color.surface_hi) { showAddApp() })
            actions.addView(chip("Reset", null, R.color.surface_hi) { appStore.save(AppPresets.defaults); afterAppsChanged() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            card.addView(actions)
        }

        section(body, "About") { card ->
            val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)) }
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            pad.addView(text("${getString(R.string.app_name)} $version", 15f, R.color.text, bold = true))
            pad.addView(text("No ads. No tracking. The app only talks to your TV on your home network.", 13f, R.color.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            pad.addView(chip("Privacy policy", null, R.color.surface_hi) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            card.addView(pad)
        }
    }

    private fun section(parent: LinearLayout, title: String, fill: (LinearLayout) -> Unit): View {
        val heading = text(title.uppercase(), 12f, R.color.muted, bold = true).apply { letterSpacing = 0.06f }
        parent.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(8); marginStart = dp(4) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color(R.color.surface)) }
            clipToOutline = true
        }
        fill(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))
        return heading
    }

    private fun optionRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple(GradientDrawable().apply { setColor(color(if (selected) R.color.accent_container else R.color.surface)) })
        setOnClickListener { onClick() }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, if (selected) R.color.accent else R.color.text, bold = true))
        texts.addView(text(detail, 13f, R.color.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(color(R.color.accent)) else setStroke(dp(2), color(R.color.muted))
            }
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- view helpers

    private fun key(
        icon: Int, desc: String, code: Int, hold: Hold, size: Int = 60,
        bg: Int? = R.color.surface, tint: Int = R.color.text,
    ): ImageButton = iconButton(icon, desc, size, bg, tint).also { bindKey(it, code, hold) }

    private fun iconButton(icon: Int, desc: String, size: Int, bg: Int? = R.color.surface, tint: Int = R.color.text) =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(tint))
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER
            background = if (bg == null) ripple(null) else circle(bg)
            minimumWidth = dp(size); minimumHeight = dp(size)
        }

    private fun icon(res: Int, tint: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color(tint))
    }

    /** Simple tap with press feedback + haptics; fires on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTap(v: View, action: () -> Unit) {
        v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    /**
     * Hold.NONE: one press on release. Hold.REPEAT: repeats while held (arrows, volume).
     * Hold.LONG: a quick tap is a normal press; holding sends a real long-press to the TV.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindKey(v: View, code: Int, hold: Hold) {
        v.setOnClickListener { send(code) } // used by accessibility services
        v.setOnTouchListener(object : View.OnTouchListener {
            var longSent = false
            val repeat = object : Runnable {
                override fun run() { send(code); main.postDelayed(this, 110) }
            }
            val longStart = Runnable {
                longSent = true
                send(code, KeyDirection.START_LONG)
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.drawableHotspotChanged(e.x, e.y)
                        view.isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        longSent = false
                        when (hold) {
                            Hold.REPEAT -> {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                send(code); main.postDelayed(repeat, 450)
                            }
                            Hold.LONG -> {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                main.postDelayed(longStart, 500)
                            }
                            Hold.NONE -> {}
                        }
                    }
                    MotionEvent.ACTION_MOVE -> view.drawableHotspotChanged(e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val released = e.actionMasked == MotionEvent.ACTION_UP
                        view.isPressed = false
                        main.removeCallbacks(repeat)
                        main.removeCallbacks(longStart)
                        when (hold) {
                            Hold.LONG -> if (longSent) send(code, KeyDirection.END_LONG) else if (released) send(code)
                            Hold.NONE -> if (released) send(code)
                            Hold.REPEAT -> {}
                        }
                    }
                }
                return true
            }
        })
    }

    private fun labeled(v: View, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(v, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(text(label, 12f, R.color.muted).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    /** Lays children out in equal-width columns, each centered in its column. */
    private fun evenRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(FrameLayout(context).apply { addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun chip(
        label: String, icon: Int?, bg: Int = R.color.surface, fg: Int = R.color.text,
        small: Boolean = false, onClick: () -> Unit,
    ) = TextView(this).apply {
        text = label
        textSize = if (small) 12f else 14f
        setTextColor(color(fg))
        if (!small) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        if (small) setPadding(dp(10), dp(6), dp(12), dp(6)) else setPadding(dp(18), dp(13), dp(18), dp(13))
        background = rounded(bg, 20)
        if (icon != null) {
            val s = dp(if (small) 16 else 20)
            val d = getDrawable(icon)!!.mutate().apply { setTint(color(fg)); setBounds(0, 0, s, s) }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(6)
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(colorRes: Int, radiusDp: Int) = ripple(GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(color(colorRes))
    })

    private fun circle(colorRes: Int) = ripple(GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color(colorRes))
    })

    private fun ripple(content: Drawable?): Drawable {
        val mask = content ?: GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        return RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), content, mask)
    }

    private fun color(res: Int) = getColor(res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun wrap() = LinearLayout.LayoutParams(-2, -2)

    companion object {
        private const val NAV_HEIGHT = 208
        private const val SYNC_TIMEOUT_MS = 900L
        private const val KEYS_TITLE = "Typing on TV (key by key)"
        private const val KEY_HOST = "host"
        private const val KEY_NAME = "name"
        private const val KEY_PAIRED = "paired_hosts"
        private const val KEY_TRACKPAD = "use_trackpad"
        private const val KEY_MENU_ACTION = "menu_action"
        private const val KEY_MENU_HINT_SHOWN = "menu_hint_shown"
        private const val KEY_SONY_PSK = "sony_psk"
        private const val KEY_SONY_HOST = "sony_host"
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        fun isIpv4(host: String) = IPV4.matches(host)
        private const val KEY_SONY_ASKED = "sony_asked"
        const val PRIVACY_URL = "https://sasaboor64.github.io/Portfolio/tv-remote/privacy-policy.html"
        private val RECONNECT = Any()
    }
}
