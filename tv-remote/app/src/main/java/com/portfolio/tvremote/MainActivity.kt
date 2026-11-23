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
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var deviceList: LinearLayout
    private lateinit var searchingText: TextView
    private lateinit var hostInput: EditText
    private lateinit var backToRemote: TextView

    private val savedHost: String? get() = prefs.getString(KEY_HOST, null)
    private val savedName: String? get() = prefs.getString(KEY_NAME, null)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = TvDiscovery(this) { showDevices(it) }
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        devicesPanel = buildDevicesPanel()
        remotePanel = buildRemotePanel()
        root.addView(devicesPanel, match())
        root.addView(remotePanel, match())
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
        setStatus("Connecting…")
        withIdentity { id ->
            main.post {
                if (!started || savedHost != host) return@post
                lateinit var client: RemoteClient
                client = RemoteClient(host, id, object : RemoteClient.Listener {
                    override fun onReady() = main.post {
                        if (remote === client) { setReady(true); setStatus("Connected") }
                    }.let { }

                    override fun onVolume(level: Int, max: Int, muted: Boolean) = main.post {
                        if (remote === client) setStatus(if (muted) "Connected · Muted" else "Connected · Volume $level/$max")
                    }.let { }

                    override fun onDisconnected(error: Throwable?, beforeReady: Boolean) = main.post {
                        if (remote === client) onRemoteLost(host, error, beforeReady)
                    }.let { }
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
                setStatus("Can't reach the TV. Is it on and on the same Wi-Fi?", retry = true)
            beforeReady -> {
                // The TV refused our certificate: it has forgotten us, so pair again.
                setPaired(host, false)
                startPairing(host)
            }
            else -> {
                setStatus("Connection lost. Reconnecting…")
                main.postAtTime({ if (started && savedHost == host) openRemote(host) }, RECONNECT,
                    android.os.SystemClock.uptimeMillis() + 2000)
            }
        }
    }

    private fun startPairing(host: String) {
        setStatus("Pairing… look at your TV for a code")
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
                        setStatus("Pairing failed: ${e.message ?: e.javaClass.simpleName}", retry = true)
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
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
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
                setStatus("Pairing…")
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
                            setStatus("Pairing failed: ${e.message ?: e.javaClass.simpleName}", retry = true)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                client.close(); pairing = null
                setStatus("Pairing cancelled", retry = true)
            }
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- sending

    private fun send(code: Int, direction: KeyDirection = KeyDirection.SHORT) {
        remote?.takeIf { it.isReady }?.sendKey(code, direction)
    }

    private fun showKeyboardDialog() {
        val input = EditText(this).apply {
            hint = "Type to send to the TV"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Keyboard")
            .setView(box)
            .setPositiveButton("Send") { _, _ -> remote?.sendText(input.text.toString()) }
            .setNeutralButton("Delete") { _, _ -> send(KeyCode.DEL) }
            .setNegativeButton("Close", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            remote?.sendText(input.text.toString()); send(KeyCode.ENTER); dialog.dismiss(); true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
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
        remoteControls.alpha = if (ready) 1f else 0.45f
    }

    private fun setStatus(text: String, retry: Boolean = false) {
        statusText.text = if (retry) "$text  ·  Tap to retry" else text
        statusText.setTextColor(color(if (retry) R.color.danger else R.color.muted))
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
                background = pill(R.color.surface, 16)
                setOnClickListener { selectTv(tv.host, tv.name) }
            }
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_tv) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(text(tv.name, 16f, R.color.text, bold = true))
                addView(text(tv.host, 13f, R.color.muted))
            })
            deviceList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    // ---------------------------------------------------------------- devices panel

    private fun buildDevicesPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(text("Choose your TV", 26f, R.color.text, bold = true))
        col.addView(text("Your phone and the TV need to be on the same Wi-Fi network.", 14f, R.color.muted).apply {
            setPadding(0, dp(6), 0, dp(20))
        })
        searchingText = text("Searching for TVs on your network…", 13f, R.color.muted).apply { setPadding(0, 0, 0, dp(10)) }
        col.addView(searchingText)
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(deviceList)

        col.addView(text("Or enter the TV's IP address", 13f, R.color.muted).apply { setPadding(0, dp(24), 0, dp(8)) })
        col.addView(text("On the TV: Settings → Network & Internet → your network.", 12f, R.color.muted).apply {
            setPadding(0, 0, 0, dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hostInput = EditText(this).apply {
            hint = "192.168.1.50"
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.muted))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = pill(R.color.surface, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnEditorActionListener { _, _, _ -> connectManual(); true }
        }
        row.addView(hostInput, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Connect", null) { connectManual() }.apply { background = pill(R.color.accent, 14) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        col.addView(row)

        backToRemote = chip("Back to remote", R.drawable.ic_back) {
            showRemotePanel(); savedHost?.let { connect(it) }
        }
        col.addView(backToRemote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })

        col.addView(chip("Forget paired TVs", null) {
            prefs.edit().remove(KEY_PAIRED).remove(KEY_HOST).remove(KEY_NAME).apply()
            backToRemote.visibility = View.GONE
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        return ScrollView(this).apply { addView(col) }
    }

    private fun connectManual() {
        val host = hostInput.text.toString().trim()
        if (host.isNotEmpty()) selectTv(host, host)
    }

    // ---------------------------------------------------------------- remote panel

    private fun buildRemotePanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }

        // Header: devices · name/status · power
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_tv, "Choose TV", 48, R.color.surface).apply {
            setOnClickListener { showDevicesPanel() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        tvName = text("TV", 18f, R.color.text, bold = true).apply { maxLines = 1 }
        statusText = text("Not connected", 13f, R.color.muted).apply { maxLines = 2 }
        titles.addView(tvName); titles.addView(statusText)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(key(R.drawable.ic_power, "Power", KeyCode.POWER, Hold.NONE, 48, R.color.danger))
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        remoteControls = controls

        // Back · Home · Menu
        controls.addView(row(
            labeled(key(R.drawable.ic_back, "Back", KeyCode.BACK, Hold.LONG), "Back"),
            labeled(key(R.drawable.ic_home, "Home", KeyCode.HOME, Hold.LONG), "Home"),
            labeled(key(R.drawable.ic_menu, "Menu", KeyCode.MENU, Hold.LONG), "Menu"),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        controls.addView(buildDpad(), LinearLayout.LayoutParams(dp(264), dp(264)).apply { topMargin = dp(24) })

        // Volume · media · channel
        controls.addView(row(
            key(R.drawable.ic_vol_down, "Volume down", KeyCode.VOLUME_DOWN, Hold.REPEAT),
            key(R.drawable.ic_mute, "Mute", KeyCode.MUTE, Hold.NONE),
            key(R.drawable.ic_vol_up, "Volume up", KeyCode.VOLUME_UP, Hold.REPEAT),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        controls.addView(row(
            key(R.drawable.ic_rew, "Rewind", KeyCode.MEDIA_REWIND, Hold.REPEAT),
            key(R.drawable.ic_play_pause, "Play or pause", KeyCode.MEDIA_PLAY_PAUSE, Hold.NONE),
            key(R.drawable.ic_ff, "Fast forward", KeyCode.MEDIA_FAST_FORWARD, Hold.REPEAT),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        controls.addView(row(
            key(R.drawable.ic_ch_down, "Channel down", KeyCode.CHANNEL_DOWN, Hold.REPEAT),
            key(R.drawable.ic_input, "Input source", KeyCode.TV_INPUT, Hold.NONE),
            key(R.drawable.ic_ch_up, "Channel up", KeyCode.CHANNEL_UP, Hold.REPEAT),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Extras
        val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        extras.addView(chip("Keyboard", R.drawable.ic_keyboard) { showKeyboardDialog() })
        extras.addView(chip("Settings", null) { send(KeyCode.SETTINGS) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        extras.addView(chip("Info", null) { send(KeyCode.INFO) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        controls.addView(extras, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        col.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setReady(false)
        return ScrollView(this).apply { isFillViewport = true; addView(col) }
    }

    private fun buildDpad(): View {
        val pad = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.surface)) }
        }
        val arrow = 84
        fun arrowKey(icon: Int, desc: String, code: Int, gravity: Int) =
            pad.addView(key(icon, desc, code, Hold.REPEAT, arrow, null, circle = true),
                FrameLayout.LayoutParams(dp(arrow), dp(arrow), gravity))
        arrowKey(R.drawable.ic_up, "Up", KeyCode.DPAD_UP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        arrowKey(R.drawable.ic_down, "Down", KeyCode.DPAD_DOWN, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        arrowKey(R.drawable.ic_left, "Left", KeyCode.DPAD_LEFT, Gravity.START or Gravity.CENTER_VERTICAL)
        arrowKey(R.drawable.ic_right, "Right", KeyCode.DPAD_RIGHT, Gravity.END or Gravity.CENTER_VERTICAL)
        val ok = TextView(this).apply {
            text = "OK"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            contentDescription = "OK"
            background = circleBg(R.color.surface_hi)
        }
        bindKey(ok, KeyCode.DPAD_CENTER, Hold.LONG)
        pad.addView(ok, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        return pad
    }

    // ---------------------------------------------------------------- view helpers

    private fun key(
        icon: Int, desc: String, code: Int, hold: Hold,
        size: Int = 64, bg: Int? = R.color.surface, circle: Boolean = false,
    ): ImageButton = iconButton(icon, desc, size, bg, circle).also { bindKey(it, code, hold) }

    private fun iconButton(icon: Int, desc: String, size: Int, bg: Int?, circle: Boolean = false) =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(R.color.text))
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER
            background = when {
                bg == null -> ripple(null)
                circle -> circleBg(bg)
                else -> pill(bg, 18)
            }
            layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
            minimumWidth = dp(size); minimumHeight = dp(size)
        }

    /**
     * Hold.NONE: one press on release. Hold.REPEAT: repeats while held (arrows, volume).
     * Hold.LONG: a quick tap is a normal press; holding sends a real long-press to the TV
     * (e.g. long-press OK or Menu for extra options).
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
        addView(v)
        addView(text(label, 12f, R.color.muted).apply { setPadding(0, dp(6), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { v ->
            addView(FrameLayout(context).apply { addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun chip(label: String, icon: Int?, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(color(R.color.text))
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = pill(R.color.surface, 14)
        if (icon != null) {
            val d = getDrawable(icon)!!.mutate().apply {
                setTint(color(R.color.text)); setBounds(0, 0, dp(20), dp(20))
            }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
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

    private fun pill(colorRes: Int, radiusDp: Int) = ripple(GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(color(colorRes))
    })

    private fun circleBg(colorRes: Int) = ripple(GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color(colorRes))
    })

    private fun ripple(content: Drawable?): Drawable {
        val mask = content ?: GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    private fun color(res: Int) = getColor(res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun match() = FrameLayout.LayoutParams(-1, -1)

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_NAME = "name"
        private const val KEY_PAIRED = "paired_hosts"
        private val RECONNECT = Any()
    }
}
