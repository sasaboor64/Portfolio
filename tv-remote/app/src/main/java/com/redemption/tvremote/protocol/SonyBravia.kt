package com.redemption.tvremote.protocol

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sony Bravia "IP control" (HTTP on port 80). Used for Sony-only buttons the
 * Android TV remote protocol can't send, such as the Action Menu (the
 * Quick Settings bar), and to list and launch the TV's installed apps.
 *
 * The TV must have: Settings → Network → Home network → IP control →
 * Authentication "Normal and Pre-Shared Key", plus a Pre-Shared Key (PSK).
 */
class SonyBravia(val host: String, val psk: String, private val port: Int = 80) {

    open class SonyException(message: String) : IOException(message)
    class NotFound(val path: String) : SonyException("IP control is off on the TV (nothing at $path)")

    data class SonyApp(val title: String, val uri: String, val icon: String)

    private var codes: Map<String, String>? = null

    /** True if the device answers like a Bravia. Needs no PSK. */
    fun isBravia(): Boolean = runCatching {
        post("/sony/system", rpc("getInterfaceInformation"), auth = false).contains("\"result\"")
    }.getOrDefault(false)

    /** Button name -> IRCC code, as reported by the TV itself. */
    fun remoteCodes(): Map<String, String> {
        codes?.let { return it }
        val body = post("/sony/system", rpc("getRemoteControllerInfo"), auth = false)
        val map = LinkedHashMap<String, String>()
        OBJECT.findAll(body).forEach { m ->
            val name = field(m.value, "name")
            val value = field(m.value, "value")
            if (name != null && value != null) map[name] = value
        }
        if (map.isEmpty()) throw SonyException("TV did not return its remote codes")
        return map.also { codes = it }
    }

    /** Presses the first button in [names] that this TV model knows about. */
    fun pressButton(vararg names: String) {
        val all = remoteCodes()
        val code = names.firstNotNullOfOrNull { n -> all.entries.firstOrNull { it.key.equals(n, true) }?.value }
            ?: throw SonyException("This TV has no ${names.first()} button")
        sendIrcc(code)
    }

    // Some Bravias serve /sony/ircc, others only /sony/IRCC (a 404 tells us which), and some
    // ignore the first command after ~10+ idle minutes. Same handling as pybravia.
    @Volatile private var irccPath = "/sony/ircc"
    @Volatile private var irccWokenAt = 0L

    fun sendIrcc(code: String) {
        val now = System.currentTimeMillis()
        if (now - irccWokenAt > 10 * 60_000L) {
            try { postIrcc("") } catch (e: NotFound) { irccPath = swapCase(irccPath) } catch (_: IOException) {}
            irccWokenAt = now
        }
        try {
            postIrcc(code)
        } catch (e: NotFound) {
            irccPath = swapCase(irccPath)
            try {
                postIrcc(code)
            } catch (e2: NotFound) {
                throw SonyException("The TV has no remote-button service (tried /sony/ircc and /sony/IRCC)")
            }
        }
    }

    private fun swapCase(path: String) =
        if (path.endsWith("ircc")) path.removeSuffix("ircc") + "IRCC" else path.removeSuffix("IRCC") + "ircc"

    private fun postIrcc(code: String) {
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>${xml(code)}</IRCCCode>""" +
            """</u:X_SendIRCC></s:Body></s:Envelope>"""
        try {
            post(irccPath, soap, auth = true, contentType = "text/xml; charset=UTF-8",
                soapAction = "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
        } catch (e: SonyException) {
            if (e is NotFound || code.isEmpty()) throw e
            throw SonyException(if (e.message?.contains("500") == true) "The TV refused that button" else e.message ?: "IRCC failed")
        }
    }

    /** Wakes the TV from standby (needs "Remote start" on, which IP control usually enables). */
    fun powerOn() {
        checkError(post("/sony/system", rpc("setPowerStatus", """{"status":true}"""), auth = true))
    }

    fun apps(): List<SonyApp> {
        val body = post("/sony/appControl", rpc("getApplicationList"), auth = true)
        checkError(body)
        return OBJECT.findAll(body).mapNotNull { m ->
            val title = field(m.value, "title") ?: return@mapNotNull null
            val uri = field(m.value, "uri") ?: return@mapNotNull null
            SonyApp(title, uri, field(m.value, "icon") ?: "")
        }.toList()
    }

    fun launch(uri: String) {
        checkError(post("/sony/appControl", rpc("setActiveApp", """{"uri":"${json(uri)}"}"""), auth = true))
    }

    private fun checkError(body: String) {
        if (body.contains("\"error\"")) {
            val code = Regex("\"error\"\\s*:\\s*\\[\\s*(\\d+)").find(body)?.groupValues?.get(1)
            throw SonyException(when (code) {
                "403" -> "TV rejected the Pre-Shared Key"
                else -> "TV returned error ${code ?: ""}".trim()
            })
        }
    }

    private fun post(
        path: String, body: String, auth: Boolean,
        contentType: String = "application/json; charset=UTF-8", soapAction: String? = null,
    ): String {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (soapAction != null) conn.setRequestProperty("SOAPACTION", soapAction)
            if (auth) conn.setRequestProperty("X-Auth-PSK", psk)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status == 403 || status == 401) throw SonyException("TV rejected the Pre-Shared Key")
            if (status == 404) throw NotFound(path)
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw SonyException("TV answered HTTP $status")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Sony names for the button that opens the Quick Settings / Action Menu bar. */
        val ACTION_MENU_NAMES = arrayOf("ActionMenu", "QuickSettings", "Options")

        private val OBJECT = Regex("\\{[^{}\\[\\]]*\\}")

        private fun field(obj: String, key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)?.groupValues?.get(1)?.let(::unescape)

        private fun unescape(s: String) = s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        private fun rpc(method: String, param: String? = null) =
            """{"method":"$method","id":1,"params":[${param ?: ""}],"version":"1.0"}"""
    }
}
