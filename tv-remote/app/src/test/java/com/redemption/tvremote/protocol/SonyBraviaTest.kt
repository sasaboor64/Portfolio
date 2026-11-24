package com.redemption.tvremote.protocol

import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SonyBraviaTest {
    data class Req(val path: String, val psk: String?, val soap: String?, val body: String)

    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<Req>()
    /** The only IRCC path this fake TV answers (TVs differ in case). */
    @Volatile private var irccPath = "/sony/IRCC"

    /** Minimal HTTP/1.1 server: one request per connection. */
    @Before fun start() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val input = s.getInputStream().buffered()
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
                        return sb.toString()
                    }
                    val path = line().split(" ")[1]
                    val headers = HashMap<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; val i = l.indexOf(':'); headers[l.substring(0, i).lowercase()] = l.substring(i + 1).trim() }
                    val len = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(len).also { var r = 0; while (r < len) r += input.read(it, r, len - r) }.toString(Charsets.UTF_8)
                    val psk = headers["x-auth-psk"]
                    requests.add(Req(path, psk, headers["soapaction"], body))
                    val notFound = path.startsWith("/sony/") && path.equals("/sony/ircc", true) && path != irccPath
                    val reply = (if (notFound) "" else respond(path, psk, body)).toByteArray()
                    val statusLine = if (notFound) "404 Not Found" else "200 OK"
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $statusLine\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(reply); flush()
                    }
                }
            }
        }
    }

    private fun respond(path: String, psk: String?, body: String): String = when {
        path == "/sony/system" && body.contains("getRemoteControllerInfo") ->
            """{"result":[{"bundled":true,"type":"RM-J1100"},[{"name":"PowerOff","value":"AAAAAQAAAAEAAAAvAw=="},
              {"name":"ActionMenu","value":"AAAAAgAAAMQAAABLAw=="},{"name":"Options","value":"AAAAAgAAAJcAAAA2Aw=="}]],"id":1}"""
        path == "/sony/system" -> """{"result":[{"productName":"BRAVIA"}],"id":1}"""
        psk != "1234" -> """{"error":[403,"Forbidden"],"id":1}"""
        path.equals("/sony/ircc", true) -> "<ok/>"
        body.contains("getApplicationList") ->
            """{"result":[[{"title":"Netflix","uri":"com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity","icon":"http:\/\/tv\/n.png"},
              {"uri":"com.sony.dtv.yt","title":"YouTube \"TV\"","icon":""}]],"id":1}"""
        else -> """{"result":[],"id":1}"""
    }

    @After fun stop() = server.close()

    private fun tv(psk: String = "1234") = SonyBravia("127.0.0.1", psk, server.localPort)

    @Test fun actionMenuSendsTheCodeTheTvReports() {
        val tv = tv()
        assertTrue(tv.isBravia())
        tv.pressButton(*SonyBravia.ACTION_MENU_NAMES)
        val ircc = requests.last()
        assertEquals("/sony/IRCC", ircc.path)
        assertEquals("1234", ircc.psk)
        assertEquals("\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"", ircc.soap)
        assertTrue(ircc.body.contains("<IRCCCode>AAAAAgAAAMQAAABLAw==</IRCCCode>"))
    }

    @Test fun findsTheIrccPathWhicheverCaseTheTvUses() {
        for (served in listOf("/sony/IRCC", "/sony/ircc")) {
            irccPath = served
            requests.clear()
            tv().pressButton(*SonyBravia.ACTION_MENU_NAMES)
            val ircc = requests.filter { it.path.equals("/sony/ircc", true) }
            assertEquals(served, ircc.last().path)
            assertTrue(ircc.last().body.contains("AAAAAgAAAMQAAABLAw=="))
            // The first IRCC call is an empty wake-up request.
            assertTrue(ircc.first().body.contains("<IRCCCode></IRCCCode>"))
        }
    }

    @Test fun listsAndLaunchesApps() {
        val apps = tv().apps()
        assertEquals(listOf("Netflix", "YouTube \"TV\""), apps.map { it.title })
        assertEquals("http://tv/n.png", apps[0].icon)
        tv().launch(apps[0].uri)
        assertTrue(requests.last().body.contains("\"setActiveApp\""))
        assertTrue(requests.last().body.contains(apps[0].uri))
    }

    @Test fun wrongPskIsReported() {
        try { tv("0000").apps(); fail() } catch (e: SonyBravia.SonyException) {
            assertEquals("TV rejected the Pre-Shared Key", e.message)
        }
    }
}
