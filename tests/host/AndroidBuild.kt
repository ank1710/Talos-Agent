package android.os

// Deterministic environment for prompt size accounting, not device evidence.
object Build {
    @JvmField val MANUFACTURER = "teste"
    @JvmField val MODEL = "teste"
    @JvmField val SUPPORTED_ABIS = arrayOf("arm64-v8a")
    object VERSION {
        @JvmField val RELEASE = "16"
        const val SDK_INT = 36
    }
}
