package android.security

/** Host-side equivalent of Android's cleartext policy for the loopback test server. */
class NetworkSecurityPolicy private constructor() {
    @Suppress("UNUSED_PARAMETER")
    fun isCleartextTrafficPermitted(hostname: String?): Boolean = true

    companion object {
        private val INSTANCE = NetworkSecurityPolicy()
        @JvmStatic fun getInstance(): NetworkSecurityPolicy = INSTANCE
    }
}
