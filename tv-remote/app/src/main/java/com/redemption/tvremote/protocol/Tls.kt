package com.redemption.tvremote.protocol

import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** The client key + self-signed certificate the TV remembers after pairing. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    companion object {
        private val PASSWORD = "tvremote".toCharArray()
        private const val ALIAS = "client"

        /** Loads the identity from [file], creating and saving a new one the first time. */
        fun loadOrCreate(file: File, commonName: String = "tvremote"): ClientIdentity {
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                runCatching {
                    file.inputStream().use { ks.load(it, PASSWORD) }
                    val key = ks.getKey(ALIAS, PASSWORD) as PrivateKey
                    val cert = ks.getCertificate(ALIAS) as X509Certificate
                    return ClientIdentity(key, cert)
                }
            }
            val identity = generate(commonName)
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, identity.privateKey, PASSWORD, arrayOf(identity.certificate))
            file.parentFile?.mkdirs()
            file.outputStream().use { ks.store(it, PASSWORD) }
            return identity
        }

        fun generate(commonName: String): ClientIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(63, SecureRandom()),
                Date(now - 24L * 3600 * 1000),
                Date(now + 20L * 365 * 24 * 3600 * 1000),
                name,
                kp.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return ClientIdentity(kp.private, cert)
        }
    }
}

/** SHA-256 of a certificate's public key, as hex. Stays the same if the TV re-issues its cert with the same key. */
fun X509Certificate.pin(): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).joinToString("") { "%02x".format(it) }

/**
 * Trust-on-first-use. TVs use self-signed certificates, so there is no CA to check against:
 * - [expectedPin] == null (pairing, or the first connection right after it): the certificate
 *   is accepted and recorded in [seenPin]; pairing then proves it cryptographically with the code.
 * - [expectedPin] set: the certificate must have exactly that public key, or the connection fails.
 */
class PinnedTrustManager(private val expectedPin: String?) : X509TrustManager {
    @Volatile var seenPin: String? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("This app only connects as a client")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        leaf.checkValidityLenient()
        val pin = leaf.pin()
        if (expectedPin != null && !pin.equals(expectedPin, ignoreCase = true)) {
            throw CertificateException("TV certificate changed (expected $expectedPin, got $pin)")
        }
        seenPin = pin
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    // Many TVs ship certificates with odd validity dates and clocks may be unset in standby,
    // so expiry is not enforced; a certificate that is not yet valid by years is still refused.
    private fun X509Certificate.checkValidityLenient() {
        val tenYears = 10L * 365 * 24 * 3600 * 1000
        if (notBefore.time > System.currentTimeMillis() + tenYears) throw CertificateException("Certificate not valid yet")
    }
}

internal object Tls {
    fun context(identity: ClientIdentity, trust: X509TrustManager): SSLContext {
        val pw = CharArray(0)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("client", identity.privateKey, pw, arrayOf(identity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pw)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trust), SecureRandom()) }
    }

    fun connect(
        host: String, port: Int, identity: ClientIdentity, trust: X509TrustManager, timeoutMs: Int = 5000,
    ): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.tcpNoDelay = true
            val socket = context(identity, trust).socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.useClientMode = true
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }
}
