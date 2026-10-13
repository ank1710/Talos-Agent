package android.util

/** Minimal Android logging shim for OkHttp's Android platform detector in host checks. */
object Log {
    @JvmStatic fun isLoggable(tag: String?, priority: Int): Boolean = false
    @JvmStatic fun getStackTraceString(error: Throwable?): String = error?.stackTraceToString().orEmpty()
    @JvmStatic fun println(priority: Int, tag: String?, message: String?): Int = 0
    @JvmStatic fun i(tag: String?, message: String?, error: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, message: String?, error: Throwable?): Int = 0
}

/** No-op host substitute; OkHttp stores one while tracking a call's lifetime. */
class CloseGuard {
    fun open(closer: String?) = Unit
    fun warnIfOpen() = Unit
}
