package com.redemption.tvremote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real clients against a fake TV speaking the TV side of both protocols. */
class FakeTvTest {

    private val tvIdentity = ClientIdentity.generate("FakeTV")
    private val phone = ClientIdentity.generate("tvremote")

    private fun serverSocket(): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("tv", tvIdentity.privateKey, CharArray(0), arrayOf(tvIdentity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
        return (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    }

    @Test
    fun protoRoundTrip() {
        val bytes = ProtoWriter()
            .varint(1, 300)
            .string(2, "héllo")
            .message(10) { varint(1, 82); varint(2, 3) }
            .toByteArray()
        val m = ProtoMessage.parse(bytes)
        assertEquals(300, m.int(1))
        assertEquals("héllo", m.string(2))
        assertEquals(82, m.message(10)!!.int(1))
        assertEquals(3, m.message(10)!!.int(2))
        assertFalse(m.has(5))

        val out = ByteArrayOutputStream()
        Framing.write(out, ByteArray(200) { it.toByte() })
        Framing.write(out, byteArrayOf(7))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(200, Framing.read(input)!!.size)
        assertArrayEquals(byteArrayOf(7), Framing.read(input))
        assertNull(Framing.read(input))
    }

    @Test
    fun pairingWithCodeShownOnTv() {
        val server = serverSocket()
        val nonce = byteArrayOf(0x3A, 0x7F)
        val received = LinkedBlockingQueue<ProtoMessage>()
        var code = ""
        val codeShown = CountDownLatch(1)
        var secretOk = false

        val tv = thread {
            (server.accept() as SSLSocket).use { s ->
                s.startHandshake()
                val clientCert = s.session.peerCertificates[0] as X509Certificate
                val expected = PairingSecret.hash(clientCert, tvIdentity.certificate, nonce)
                code = "%02X%02X%02X".format(expected[0].toInt() and 0xFF, nonce[0], nonce[1])
                fun reply(field: Int) = Framing.write(s.outputStream,
                    ProtoWriter().varint(1, 2).varint(2, 200).message(field) {}.toByteArray())
                repeat(3) { step ->
                    val m = ProtoMessage.parse(Framing.read(s.inputStream)!!)
                    received.add(m)
                    reply(listOf(11, 20, 31)[step])
                }
                codeShown.countDown()
                val secretMsg = ProtoMessage.parse(Framing.read(s.inputStream)!!)
                secretOk = secretMsg.message(40)!!.bytes(1)!!.contentEquals(expected)
                reply(41)
            }
        }

        val client = PairingClient("127.0.0.1", phone, server.localPort)
        client.start("Test Phone")
        assertTrue(codeShown.await(5, TimeUnit.SECONDS))

        val request = received.take()
        assertEquals(200, request.int(2))
        assertEquals("atvremote", request.message(10)!!.string(1))
        assertEquals("Test Phone", request.message(10)!!.string(2))
        val options = received.take().message(20)!!
        assertEquals(3, options.message(1)!!.int(1)) // hexadecimal
        assertEquals(6, options.message(1)!!.int(2))
        assertEquals(1, received.take().message(30)!!.int(2)) // input role

        // A typo is caught locally before anything is sent.
        val typo = (code.substring(0, 2).toInt(16) xor 1).let { "%02X".format(it) } + code.substring(2)
        assertFalse(client.isCodeValid(typo))
        assertTrue(client.isCodeValid(code.lowercase()))

        client.finish(code.lowercase())
        tv.join(5000)
        assertTrue("TV should accept the secret", secretOk)
        server.close()
    }

    @Test
    fun remoteHandshakePingKeysAndVolume() {
        val server = serverSocket()
        val fromPhone = LinkedBlockingQueue<ProtoMessage>()
        val tv = thread {
            (server.accept() as SSLSocket).use { s ->
                val out = s.outputStream
                val input = s.inputStream
                fun send(w: ProtoWriter) = Framing.write(out, w.toByteArray())
                fun next() = ProtoMessage.parse(Framing.read(input)!!).also { fromPhone.add(it) }

                send(ProtoWriter().message(1) { varint(1, 622); message(2) { string(1, "FakeTV") } })
                next() // configure reply
                send(ProtoWriter().message(2) { varint(1, 622) })
                next() // set active reply
                send(ProtoWriter().message(8) { varint(1, 42) })
                next() // ping response
                send(ProtoWriter().message(50) { varint(6, 100); varint(7, 17); bool(8, false) })
                send(ProtoWriter().message(40) { bool(1, true) })
                next() // MENU short
                next() // MENU start long
                next() // MENU end long
                next(); next() // "ok" typed as keys (no text field focused)
                next() // app link
            }
        }

        val ready = CountDownLatch(1)
        val volume = CountDownLatch(1)
        val power = CountDownLatch(1)
        var vol = 0
        val client = RemoteClient("127.0.0.1", phone, object : RemoteClient.Listener {
            override fun onReady() = ready.countDown()
            override fun onVolume(level: Int, max: Int, muted: Boolean) { vol = level; volume.countDown() }
            override fun onPower(isOn: Boolean) { if (isOn) power.countDown() }
        }, deviceModel = "Pixel", deviceVendor = "Google", port = server.localPort)
        client.connect()

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val configure = fromPhone.poll(5, TimeUnit.SECONDS)!!.message(1)!!
        assertEquals(622, configure.int(1))
        assertEquals("Pixel", configure.message(2)!!.string(1))
        assertEquals(622, fromPhone.poll(5, TimeUnit.SECONDS)!!.message(2)!!.int(1))
        assertEquals(42, fromPhone.poll(5, TimeUnit.SECONDS)!!.message(9)!!.int(1))
        assertTrue(volume.await(5, TimeUnit.SECONDS)); assertEquals(17, vol)
        assertTrue(power.await(5, TimeUnit.SECONDS))

        client.sendKey(KeyCode.MENU)
        client.sendKey(KeyCode.MENU, KeyDirection.START_LONG)
        client.sendKey(KeyCode.MENU, KeyDirection.END_LONG)
        client.typeKeys("ok")
        client.openLink("https://example.com")

        fun key(m: ProtoMessage) = m.message(10)!!.let { it.int(1) to it.int(2) }
        assertEquals(82 to 3, key(fromPhone.poll(5, TimeUnit.SECONDS)!!))
        assertEquals(82 to 1, key(fromPhone.poll(5, TimeUnit.SECONDS)!!))
        assertEquals(82 to 2, key(fromPhone.poll(5, TimeUnit.SECONDS)!!))
        assertEquals(KeyCode.A + ('o' - 'a') to 3, key(fromPhone.poll(5, TimeUnit.SECONDS)!!))
        assertEquals(KeyCode.A + ('k' - 'a') to 3, key(fromPhone.poll(5, TimeUnit.SECONDS)!!))
        assertEquals("https://example.com", fromPhone.poll(5, TimeUnit.SECONDS)!!.message(90)!!.string(1))

        tv.join(5000)
        client.close()
        server.close()
    }

    @Test
    fun textFieldCountersFollowTheTv() {
        val server = serverSocket()
        val fromPhone = LinkedBlockingQueue<ProtoMessage>()
        val step = LinkedBlockingQueue<Int>()
        val tv = thread {
            (server.accept() as SSLSocket).use { s ->
                fun send(w: ProtoWriter) = Framing.write(s.outputStream, w.toByteArray())
                fun next() = ProtoMessage.parse(Framing.read(s.inputStream)!!).also { fromPhone.add(it) }
                send(ProtoWriter().message(1) { varint(1, 622) }); next()
                send(ProtoWriter().message(2) { varint(1, 622) }); next()
                // Keyboard opens on a search box that already says "stranger" (as in androidtvremote2 #51).
                send(ProtoWriter().message(20) {
                    message(1) { varint(1, 3); string(12, "com.google.android.tvlauncher") }
                    message(2) { varint(1, 71); string(2, "stranger"); varint(3, 8); varint(4, 8); string(6, "Search") }
                })
                step.take(); next() // first edit
                send(ProtoWriter().message(22) { message(2) { varint(1, 72); string(2, "stranger things") } })
                step.take(); next() // second edit
            }
        }
        val fields = LinkedBlockingQueue<Pair<RemoteClient.TextField?, Boolean>>()
        val client = RemoteClient("127.0.0.1", phone, object : RemoteClient.Listener {
            override fun onTextField(field: RemoteClient.TextField?, opened: Boolean) { fields.add(field to opened) }
        }, port = server.localPort)
        client.connect()
        fromPhone.poll(5, TimeUnit.SECONDS); fromPhone.poll(5, TimeUnit.SECONDS)

        val (opened, isOpen) = fields.poll(5, TimeUnit.SECONDS)!!
        assertTrue(isOpen)
        assertEquals("stranger", opened!!.value)
        assertEquals("Search", opened.label)
        assertTrue(client.hasTextField)

        client.insertText(" things"); step.add(1)
        val edit1 = fromPhone.poll(5, TimeUnit.SECONDS)!!.message(21)!!
        assertEquals(3, edit1.int(1))   // ime counter from app_info.counter
        assertEquals(71, edit1.int(2))  // field counter from the key inject
        val obj = edit1.message(3)!!.message(2)!!
        assertEquals(" things", obj.string(3))
        assertEquals(6, obj.int(1)); assertEquals(6, obj.int(2))

        val (echo, echoOpened) = fields.poll(5, TimeUnit.SECONDS)!!
        assertFalse(echoOpened)
        assertEquals("stranger things", echo!!.value)
        client.insertText("!"); step.add(2)
        val edit2 = fromPhone.poll(5, TimeUnit.SECONDS)!!.message(21)!!
        assertEquals(3, edit2.int(1))
        assertEquals(72, edit2.int(2))  // the newest field counter from the echo

        tv.join(5000); client.close(); server.close()
    }

    @Test
    fun unpairedClientIsReportedAsDisconnectedBeforeReady() {
        val server = serverSocket()
        thread { runCatching { server.accept().close() } }
        val done = CountDownLatch(1)
        var before: Boolean? = null
        RemoteClient("127.0.0.1", phone, object : RemoteClient.Listener {
            override fun onDisconnected(error: Throwable?, beforeReady: Boolean) { before = beforeReady; done.countDown() }
        }, port = server.localPort).connect()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(true, before)
        server.close()
    }

    @Test
    fun identityIsPersisted() {
        val f = kotlin.io.path.createTempFile(suffix = ".p12").toFile().apply { delete() }
        val a = ClientIdentity.loadOrCreate(f)
        val b = ClientIdentity.loadOrCreate(f)
        assertNotNull(a.certificate)
        assertArrayEquals(a.certificate.encoded, b.certificate.encoded)
        f.delete()
    }
}
