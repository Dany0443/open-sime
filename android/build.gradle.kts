// Root build file for Open SIME Android.
// Note: AGP 9.x provides built-in Kotlin support; do not apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

