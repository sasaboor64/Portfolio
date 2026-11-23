package com.portfolio.tvremote.protocol

import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String) : IOException(message)

/**
 * Pairing ("polo") protocol on port 6467.
 *
 * 1. [start] connects and negotiates; the TV then shows a 6-character hex code.
 * 2. [finish] sends the hash proving we saw that code. After that the TV accepts
 *    our client certificate on the remote port.
 */
class PairingClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val port: Int = DEFAULT_PORT,
) : Closeable {

    private var socket: SSLSocket? = null
    private var serverCert: X509Certificate? = null

    fun start(clientName: String) {
        val s = Tls.connect(host, port, identity)
        socket = s
        s.soTimeout = 15_000
        serverCert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw PairingException("TV did not send a certificate")

        send { message(F_PAIRING_REQUEST) { string(1, SERVICE_NAME); string(2, clientName) } }
        receive()
        send {
            message(F_OPTIONS) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(3, ROLE_INPUT)
            }
        }
        receive()
        send {
            message(F_CONFIGURATION) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(2, ROLE_INPUT)
            }
        }
        receive()
        // The TV is now displaying the code; the user may take a while to type it.
        s.soTimeout = 0
    }

    /** True if [code] matches what the TV is showing (checked locally, nothing is sent). */
    fun isCodeValid(code: String): Boolean {
        val cert = serverCert ?: return false
        return PairingSecret.compute(identity.certificate, cert, code) != null
    }

    fun finish(code: String) {
        val cert = serverCert ?: throw IllegalStateException("start() first")
        val secret = PairingSecret.compute(identity.certificate, cert, code)
            ?: throw PairingException("That code doesn't match the one on the TV")
        socket?.soTimeout = 15_000
        send { message(F_SECRET) { bytes(1, secret) } }
        receive()
        close()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(build: ProtoWriter.() -> Unit) {
        val msg = ProtoWriter().varint(1, PROTOCOL_VERSION).varint(2, STATUS_OK).apply(build)
        val s = socket ?: throw IOException("Not connected")
        Framing.write(s.outputStream, msg.toByteArray())
    }

    private fun receive(): ProtoMessage {
        val s = socket ?: throw IOException("Not connected")
        val bytes = Framing.read(s.inputStream) ?: throw PairingException("TV closed the pairing connection")
        val msg = ProtoMessage.parse(bytes)
        val status = msg.int(2)
        if (status != STATUS_OK) {
            throw PairingException(
                when (status) {
                    STATUS_BAD_SECRET -> "Wrong code"
                    STATUS_BAD_CONFIGURATION -> "TV rejected the pairing settings"
                    else -> "Pairing failed (status $status)"
                }
            )
        }
        return msg
    }

    companion object {
        const val DEFAULT_PORT = 6467
        const val SERVICE_NAME = "atvremote"

        const val PROTOCOL_VERSION = 2
        const val STATUS_OK = 200
        const val STATUS_ERROR = 400
        const val STATUS_BAD_CONFIGURATION = 401
        const val STATUS_BAD_SECRET = 402

        const val F_PAIRING_REQUEST = 10
        const val F_PAIRING_REQUEST_ACK = 11
        const val F_OPTIONS = 20
        const val F_CONFIGURATION = 30
        const val F_CONFIGURATION_ACK = 31
        const val F_SECRET = 40
        const val F_SECRET_ACK = 41

        const val ENCODING_HEXADECIMAL = 3
        const val CODE_LENGTH = 6
        const val ROLE_INPUT = 1
    }
}

object PairingSecret {
    /**
     * SHA-256 over (client modulus, client exponent, server modulus, server exponent,
     * last 4 hex digits of the code). The first byte of the hash must equal the first
     * 2 hex digits of the code, which lets us catch typos before sending.
     */
    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray? {
        val c = code.trim().uppercase()
        if (c.length != PairingClient.CODE_LENGTH || !c.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val hash = hash(client, server, hexToBytes(c.substring(2)))
        return if ((hash[0].toInt() and 0xFF) == c.substring(0, 2).toInt(16)) hash else null
    }

    fun hash(client: X509Certificate, server: X509Certificate, nonce: ByteArray): ByteArray {
        val ck = client.publicKey as RSAPublicKey
        val sk = server.publicKey as RSAPublicKey
        return MessageDigest.getInstance("SHA-256").run {
            update(unsigned(ck.modulus)); update(unsigned(ck.publicExponent))
            update(unsigned(sk.modulus)); update(unsigned(sk.publicExponent))
            update(nonce)
            digest()
        }
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    internal fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
