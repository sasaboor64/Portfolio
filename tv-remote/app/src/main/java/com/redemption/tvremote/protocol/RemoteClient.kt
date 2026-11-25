package com.redemption.tvremote.protocol

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/**
 * Remote-control connection on port 6466. Requires a client certificate the TV has
 * already paired with. Listener callbacks run on a background thread.
 */
class RemoteClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val listener: Listener,
    private val deviceModel: String = "Phone",
    private val deviceVendor: String = "Android",
    private val port: Int = DEFAULT_PORT,
    /** Fingerprint the TV's certificate must match; null to accept and record it (first use). */
    private val expectedPin: String? = null,
) : Closeable {

    /** Fingerprint of the TV's certificate on this connection, once connected. */
    @Volatile var serverPin: String? = null
        private set

    interface Listener {
        fun onReady() {}
        /** [beforeReady] = the TV dropped us during setup, which usually means it needs pairing. */
        fun onDisconnected(error: Throwable?, beforeReady: Boolean) {}
        fun onVolume(level: Int, max: Int, muted: Boolean) {}
        fun onPower(isOn: Boolean) {}
        fun onCurrentApp(packageName: String) {}
        /**
         * A text field on the TV opened ([opened] = true) or reported its new contents after an
         * edit ([opened] = false). [field] is null when the TV reports no shared text field.
         */
        fun onTextField(field: TextField?, opened: Boolean) {}
        /** Model and maker the TV reports about itself, e.g. ("BRAVIA 4K UR2", "Sony"). */
        fun onDeviceInfo(model: String, vendor: String) {}
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile var isReady = false
        private set
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-remote-writer") }

    // Text-input state reported by the TV when a text field has focus.
    /** Snapshot of the TV's focused text field. */
    data class TextField(val value: String, val start: Int, val end: Int, val label: String, val counter: Int)

    // Text-input counters. Every edit must quote the latest values or the TV ignores it.
    // Rules follow the reference implementation (androidtvremote2) and device reports
    // from Sony Bravia: ime counter = app_info.counter from the key-inject message; field
    // counter = the newest text_field_status.counter_field (it changes after every edit).
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var hasImeCounter = false
    @Volatile private var hasFieldCounter = false

    /** True once the TV has told us which text field has focus. */
    val hasTextField: Boolean get() = hasImeCounter || hasFieldCounter

    /** True once this TV has reported a field's contents back after an edit (Sony does). */
    @Volatile var reportsEdits = false
        private set

    /** Drop text-field info that is no longer current (e.g. the user moved to another app). */
    fun forgetTextField() {
        hasImeCounter = false
        hasFieldCounter = false
    }

    fun connect() {
        Thread({ run() }, "tv-remote-reader").apply { isDaemon = true }.start()
    }

    private fun run() {
        var error: Throwable? = null
        try {
            val trust = PinnedTrustManager(expectedPin)
            val s = Tls.connect(host, port, identity, trust)
            serverPin = trust.seenPin
            socket = s
            s.soTimeout = 0 // the TV pings every few seconds; no read timeout needed
            if (closed) return
            val input = s.inputStream
            while (!closed) {
                val bytes = Framing.read(input) ?: break
                handle(ProtoMessage.parse(bytes))
            }
        } catch (e: Throwable) {
            error = e
        } finally {
            val wasReady = isReady
            isReady = false
            runCatching { socket?.close() }
            writer.shutdown()
            if (!closed) listener.onDisconnected(error, beforeReady = !wasReady)
        }
    }

    private fun handle(msg: ProtoMessage) {
        when {
            msg.has(F_CONFIGURE) -> {
                msg.message(F_CONFIGURE)?.message(2)?.let { listener.onDeviceInfo(it.string(1), it.string(2)) }
                send {
                    message(F_CONFIGURE) {
                        varint(1, FEATURES)
                        message(2) {
                            string(1, deviceModel)
                            string(2, deviceVendor)
                            varint(3, 1)
                            string(4, "1")
                            string(5, PACKAGE_NAME)
                            string(6, APP_VERSION)
                        }
                    }
                }
            }
            msg.has(F_SET_ACTIVE) -> {
                send { message(F_SET_ACTIVE) { varint(1, FEATURES) } }
                if (!isReady) {
                    isReady = true
                    listener.onReady()
                }
            }
            msg.has(F_PING_REQUEST) -> {
                val v = msg.message(F_PING_REQUEST)?.int(1) ?: 0
                send { message(F_PING_RESPONSE) { varint(1, v) } }
            }
            msg.has(F_START) -> listener.onPower(msg.message(F_START)?.bool(1) ?: false)
            msg.has(F_SET_VOLUME_LEVEL) -> msg.message(F_SET_VOLUME_LEVEL)?.let {
                listener.onVolume(it.int(7), it.int(6), it.bool(8))
            }
            msg.has(F_IME_KEY_INJECT) -> msg.message(F_IME_KEY_INJECT)?.let { ime ->
                val app = ime.message(1)
                app?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
                imeCounter = app?.int(1) ?: 0
                hasImeCounter = imeCounter != 0
                val status = ime.message(2)
                fieldCounter = status?.int(1) ?: 0
                hasFieldCounter = fieldCounter != 0
                listener.onTextField(status?.takeIf { hasFieldCounter || hasImeCounter }?.let(::textField), opened = true)
            }
            msg.has(F_IME_SHOW_REQUEST) -> msg.message(F_IME_SHOW_REQUEST)?.message(2)?.let { status ->
                fieldCounter = status.int(1)
                hasFieldCounter = true
                reportsEdits = true
                listener.onTextField(textField(status), opened = false)
            }
            msg.has(F_IME_BATCH_EDIT) -> msg.message(F_IME_BATCH_EDIT)?.let {
                if (!hasImeCounter) imeCounter = it.int(1)
                if (!hasFieldCounter) fieldCounter = it.int(2)
            }
        }
    }

    fun sendKey(code: Int, direction: KeyDirection = KeyDirection.SHORT) {
        send { message(F_KEY_INJECT) { varint(1, code); varint(2, direction.value) } }
    }

    /**
     * Inserts [text] at the TV's cursor in the focused text field. Some apps replace the
     * whole field instead; [com.redemption.tvremote.protocol.TextSync] detects and handles that.
     */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        // Cursor value as used by the version tested on a Sony Bravia (androidtvremote2 #51).
        val cursor = text.length - 1
        send {
            message(F_IME_BATCH_EDIT) {
                varint(1, imeCounter)
                varint(2, fieldCounter)
                message(3) {
                    varint(1, 1)
                    message(2) { varint(1, cursor); varint(2, cursor); string(3, text) }
                }
            }
        }
    }

    /** Types [text] one key at a time (for apps that don't share their text field). */
    fun typeKeys(text: String) {
        text.forEach { ch -> KeyCode.forChar(ch)?.let { sendKey(it) } }
    }

    /** Opens an app link / deep link on the TV, e.g. "https://www.youtube.com". */
    fun openLink(url: String) {
        send { message(F_APP_LINK_LAUNCH) { string(1, url) } }
    }

    private fun send(build: ProtoWriter.() -> Unit) {
        val bytes = ProtoWriter().apply(build).toByteArray()
        if (writer.isShutdown) return
        runCatching {
            writer.execute {
                try {
                    val s = socket ?: throw IOException("Not connected")
                    synchronized(s) { Framing.write(s.outputStream, bytes) }
                } catch (e: IOException) {
                    runCatching { socket?.close() } // the reader thread reports the disconnect
                }
            }
        }
    }

    override fun close() {
        closed = true
        isReady = false
        writer.shutdown()
        runCatching { socket?.close() }
    }

    private fun textField(status: ProtoMessage) =
        TextField(status.string(2), status.int(3), status.int(4), status.string(6), status.int(1))

    companion object {
        const val DEFAULT_PORT = 6466
        const val PACKAGE_NAME = "atvremote"
        const val APP_VERSION = "1.0.0"
        /** Feature bitmask: ping, key, IME, power, volume, app link. */
        const val FEATURES = 622

        const val F_CONFIGURE = 1
        const val F_SET_ACTIVE = 2
        const val F_ERROR = 3
        const val F_PING_REQUEST = 8
        const val F_PING_RESPONSE = 9
        const val F_KEY_INJECT = 10
        const val F_IME_KEY_INJECT = 20
        const val F_IME_BATCH_EDIT = 21
        const val F_IME_SHOW_REQUEST = 22
        const val F_START = 40
        const val F_SET_VOLUME_LEVEL = 50
        const val F_APP_LINK_LAUNCH = 90
    }
}
