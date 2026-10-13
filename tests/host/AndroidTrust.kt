package android.net.http

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Force OkHttp's Android trust-chain extension to use its normal JVM fallback. */
class X509TrustManagerExtensions(@Suppress("UNUSED_PARAMETER") trustManager: X509TrustManager) {
    init { throw IllegalArgumentException("Android trust extensions are unavailable in host tests") }

    @Suppress("UNUSED_PARAMETER")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, hostname: String): List<X509Certificate> = emptyList()
}
