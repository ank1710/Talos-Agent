// AGP 9 ships built-in Kotlin support, so no `org.jetbrains.kotlin.android`
// plugin here: applying it is an explicit error since AGP 9.0.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
