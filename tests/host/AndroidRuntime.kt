package android.os

/** Only the slow-call marker used while OkHttp creates its host-side client. */
object StrictMode {
    @JvmStatic fun noteSlowCall(name: String?) = Unit
}
