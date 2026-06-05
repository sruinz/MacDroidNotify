package dev.svrx.macdroidnotify

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

object TlsFingerprint {
    fun normalize(value: String): String {
        return value.filter { it.digitToIntOrNull(16) != null }.uppercase()
    }

    fun isValid(value: String): Boolean {
        val normalized = normalize(value)
        return normalized.length == 64 && normalized.all { it.digitToIntOrNull(16) != null }
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }
    }
}

class PinnedCertificateTrustManager(
    expectedFingerprint: String,
) : X509TrustManager {
    private val expected = TlsFingerprint.normalize(expectedFingerprint)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TLS certificate missing")
        val actual = TlsFingerprint.sha256Hex(leaf.encoded)
        if (actual != expected) {
            throw CertificateException("TLS fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
