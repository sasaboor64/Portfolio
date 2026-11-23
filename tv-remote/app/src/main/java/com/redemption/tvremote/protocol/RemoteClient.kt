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
) : Closeable {

    interface Listener {
        fun onReady() {}
        /** [beforeReady] = the TV dropped us during setup, which usually means it needs pairing. */
        fun onDisconnected(error: Throwable?, beforeReady: Boolean) {}
        fun onVolume(level: Int, max: Int, muted: Boolean) {}
        fun onPower(isOn: Boolean) {}
        fun onCurrentApp(packageName: String) {}
        /** Model and maker the TV reports about itself, e.g. ("BRAVIA 4K UR2", "Sony"). */
        fun onDeviceInfo(model: String, vendor: String) {}
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile var isReady = false
        private set
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-remote-writer") }

    // Text-input state reported by the TV when a text field has focus.
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var imeActive = false

    fun connect() {
        Thread({ run() }, "tv-remote-reader").apply { isDaemon = true }.start()
    }

    private fun run() {
        var error: Throwable? = null
        try {
            val s = Tls.connect(host, port, identity)
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
                ime.message(1)?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
                ime.message(2)?.let { status ->
                    fieldCounter = status.int(1)
                    imeActive = true
                }
            }
            msg.has(F_IME_BATCH_EDIT) -> msg.message(F_IME_BATCH_EDIT)?.let {
                imeCounter = it.int(1)
                fieldCounter = it.int(2)
                imeActive = true
            }
            msg.has(F_IME_SHOW_REQUEST) -> imeActive = true
        }
    }

    fun sendKey(code: Int, direction: KeyDirection = KeyDirection.SHORT) {
        send { message(F_KEY_INJECT) { varint(1, code); varint(2, direction.value) } }
    }

    /**
     * Types [text] on the TV. Uses the TV's text-input channel when a text field is
     * focused there, otherwise falls back to individual key presses.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (imeActive) {
            send {
                message(F_IME_BATCH_EDIT) {
                    varint(1, imeCounter)
                    varint(2, fieldCounter)
                    message(3) {
                        varint(1, 1)
                        message(2) { varint(1, text.length - 1); varint(2, text.length - 1); string(3, text) }
                    }
                }
            }
        } else {
            text.forEach { ch -> KeyCode.forChar(ch)?.let { sendKey(it) } }
        }
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
