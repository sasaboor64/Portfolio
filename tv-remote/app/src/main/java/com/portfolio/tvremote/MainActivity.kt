package com.portfolio.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.portfolio.tvremote.protocol.ClientIdentity
import com.portfolio.tvremote.protocol.KeyCode
import com.portfolio.tvremote.protocol.KeyDirection
import com.portfolio.tvremote.protocol.PairingClient
import com.portfolio.tvremote.protocol.RemoteClient
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
        QUICK_MENU("TV quick menu", "Holds Home. Opens the Google TV / Android TV panel with picture, sound and settings."),
        SETTINGS_KEY("Settings key", "The gear / settings key found on many TV-brand remotes (TCL, Hisense, Sony, Philips)."),
        TV_MENU_KEY("TV menu key", "The TV context-menu key some remotes have."),
        APP_OPTIONS("App options", "Opens the options menu of the app on screen (key 82)."),
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
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

    private val savedHost: String? get() = prefs.getString(KEY_HOST, null)
    private val savedName: String? get() = prefs.getString(KEY_NAME, null)
    private var useTrackpad: Boolean
        get() = prefs.getBoolean(KEY_TRACKPAD, true)
        set(v) { prefs.edit().putBoolean(KEY_TRACKPAD, v).apply() }
    private var menuAction: MenuAction
        get() = runCatching { MenuAction.valueOf(prefs.getString(KEY_MENU_ACTION, null)!!) }.getOrDefault(MenuAction.QUICK_MENU)
        set(v) { prefs.edit().putString(KEY_MENU_ACTION, v.name).apply() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = TvDiscovery(this) { showDevices(it) }
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        devicesPanel = buildDevicesPanel()
        remotePanel = buildRemotePanel()
        root.addView(devicesPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(remotePanel, FrameLayout.LayoutParams(-1, -1))
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
        if (savedHost != null) showRemotePanel() else showDevicesPanel()
    }

    override fun onStart() {
        super.onStart()
        started = true
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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (devicesPanel.visibility == View.VISIBLE && savedHost != null) {
            showRemotePanel(); savedHost?.let { connect(it) }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

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

    private fun setPaired(host: String, paired: Boolean) {
        val set = prefs.getStringSet(KEY_PAIRED, emptySet())!!.toMutableSet()
        if (paired) set.add(host) else set.remove(host)
        prefs.edit().putStringSet(KEY_PAIRED, set).apply()
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
                client = RemoteClient(host, id, object : RemoteClient.Listener {
                    override fun onReady() {
                        main.post { if (remote === client) { setReady(true); setStatus("Connected", Status.OK) } }
                    }

                    override fun onVolume(level: Int, max: Int, muted: Boolean) {
                        main.post { if (remote === client) showVolume(level, max, muted) }
                    }

                    override fun onDisconnected(error: Throwable?, beforeReady: Boolean) {
                        main.post { if (remote === client) onRemoteLost(host, error, beforeReady) }
                    }
                }, deviceModel = Build.MODEL ?: "Phone", deviceVendor = Build.MANUFACTURER ?: "Android")
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
                setStatus("Can't reach the TV. Is it on, and on the same Wi-Fi?", Status.ERROR, retry = true)
            beforeReady -> {
                // The TV refused our certificate: it has forgotten us, so pair again.
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
        remote?.takeIf { it.isReady }?.sendKey(code, direction)
    }

    private fun pressMenu() {
        when (menuAction) {
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

    private fun showKeyboardDialog() {
        val input = EditText(this).apply {
            hint = "Type to send to the TV"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Keyboard")
            .setView(box)
            .setPositiveButton("Send") { _, _ -> remote?.sendText(input.text.toString()) }
            .setNeutralButton("Delete") { _, _ -> send(KeyCode.DEL) }
            .setNegativeButton("Close", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            remote?.sendText(input.text.toString()); send(KeyCode.ENTER); dialog.dismiss(); true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showRemoteSettings() {
        val actions = MenuAction.entries
        val items = actions.map { "${it.title}\n${it.detail}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Menu button opens")
            .setSingleChoiceItems(items, actions.indexOf(menuAction)) { d, which ->
                menuAction = actions[which]
                d.dismiss()
                Toast.makeText(this, "Menu button: ${actions[which].title}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (useTrackpad) "Use D-pad instead" else "Use trackpad instead") { _, _ ->
                useTrackpad = !useTrackpad; showNavigation()
            }
            .setNegativeButton("Close", null)
            .show()
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
        statusText.text = if (retry) "$text Tap to retry." else text
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
        statusText = text("Not connected", 12f, R.color.muted).apply { maxLines = 2 }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        titles.addView(tvName, LinearLayout.LayoutParams(-1, -2))
        titles.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
        device.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        header.addView(device, LinearLayout.LayoutParams(0, dp(60), 1f))
        header.addView(iconButton(R.drawable.ic_settings, "Remote settings", 48).apply {
            setOnClickListener { showRemoteSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        header.addView(key(R.drawable.ic_power, "Power", KeyCode.POWER, Hold.NONE, 48,
            bg = R.color.danger_container, tint = R.color.danger),
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remoteControls = controls

        // Back · Home · Menu
        val menuButton = iconButton(R.drawable.ic_menu, "Menu", 60).apply {
            bindTap(this) { pressMenu() }
        }
        controls.addView(evenRow(
            labeled(key(R.drawable.ic_back, "Back", KeyCode.BACK, Hold.LONG, 60), "Back"),
            labeled(key(R.drawable.ic_home, "Home", KeyCode.HOME, Hold.LONG, 60), "Home"),
            labeled(menuButton, "Menu"),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        // Navigation: trackpad or D-pad
        // The navigation area takes whatever height is left, so tall phones get a bigger trackpad.
        navHost = FrameLayout(this).apply { minimumHeight = dp(NAV_HEIGHT) }
        controls.addView(navHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })
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
            addView(iconButton(R.drawable.ic_keyboard, "Keyboard", 46).apply { setOnClickListener { showKeyboardDialog() } },
                LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(9) })
        }
        controls.addView(evenRow(
            rocker(R.drawable.ic_plus, "Volume up", KeyCode.VOLUME_UP, R.drawable.ic_minus, "Volume down", KeyCode.VOLUME_DOWN, volumeLabel),
            middle,
            rocker(R.drawable.ic_up, "Channel up", KeyCode.CHANNEL_UP, R.drawable.ic_down, "Channel down", KeyCode.CHANNEL_DOWN,
                text("CH", 12f, R.color.muted, bold = true).apply { gravity = Gravity.CENTER }),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

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
        controls.addView(media, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

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
        addView(v, LinearLayout.LayoutParams(dp(60), dp(60)))
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
        private const val NAV_HEIGHT = 252
        private const val KEY_HOST = "host"
        private const val KEY_NAME = "name"
        private const val KEY_PAIRED = "paired_hosts"
        private const val KEY_TRACKPAD = "use_trackpad"
        private const val KEY_MENU_ACTION = "menu_action"
        private const val KEY_MENU_HINT_SHOWN = "menu_hint_shown"
        private val RECONNECT = Any()
    }
}
