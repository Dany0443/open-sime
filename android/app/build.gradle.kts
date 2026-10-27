import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.isFile) {
        keystoreFile.inputStream().use { load(it) }
    }
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) {
        localFile.inputStream().use { load(it) }
    }
}

fun resolveSigningValue(vararg envKeys: String, propKey: String): String? {
    for (key in envKeys) {
        val fromEnv = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
        if (fromEnv != null) return fromEnv
    }
    return keystoreProps.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseStorePath = resolveSigningValue("SIME_ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PATH", propKey = "sime.release.storeFile")
val releaseStorePassword = resolveSigningValue("SIME_ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEYSTORE_PASSWORD", propKey = "sime.release.storePassword")
val releaseKeyAlias = resolveSigningValue("SIME_ANDROID_KEY_ALIAS", "ANDROID_KEY_ALIAS", propKey = "sime.release.keyAlias")
val releaseKeyPassword = resolveSigningValue("SIME_ANDROID_KEY_PASSWORD", "ANDROID_KEY_PASSWORD", propKey = "sime.release.keyPassword")
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
    ?: rootProject.file("open-sime-release.jks").takeIf { it.isFile }

val hasCompleteReleaseSigning =
    releaseStoreFile != null &&
        releaseStoreFile.isFile &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "md.sime.opensime"
    compileSdk = 37

    defaultConfig {
        applicationId = "md.sime.opensime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasCompleteReleaseSigning && releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Verifies that private release signing credentials are configured before building release artifacts."
    doLast {
        if (!hasCompleteReleaseSigning) {
            val missing = buildList {
                if (releaseStoreFile == null || !releaseStoreFile.isFile) {
                    add("ANDROID_KEYSTORE_PATH or SIME_ANDROID_KEYSTORE_PATH (or open-sime-release.jks / sime.release.storeFile)")
                }
                if (releaseStorePassword.isNullOrEmpty()) {
                    add("ANDROID_KEYSTORE_PASSWORD or SIME_ANDROID_KEYSTORE_PASSWORD (or sime.release.storePassword)")
                }
                if (releaseKeyAlias.isNullOrEmpty()) {
                    add("ANDROID_KEY_ALIAS or SIME_ANDROID_KEY_ALIAS (or sime.release.keyAlias)")
                }
                if (releaseKeyPassword.isNullOrEmpty()) {
                    add("ANDROID_KEY_PASSWORD or SIME_ANDROID_KEY_PASSWORD (or sime.release.keyPassword)")
                }
            }
            throw GradleException(
                "Release signing is not configured for Open SIME Android. " +
                    "Provide private credentials via environment variables or untracked android/keystore.properties. " +
                    "Missing/invalid: ${missing.joinToString(", ")}"
            )
        }
    }
}

tasks.matching {
    it.name in setOf(
        "preReleaseBuild",
        "validateSigningRelease",
        "packageRelease",
        "assembleRelease",
        "installRelease",
        "bundleRelease",
    )
}.configureEach {
    dependsOn(validateReleaseSigning)
}

if (!hasCompleteReleaseSigning) {
    tasks.register("installRelease") {
        group = "install"
        description = "Installs the Release build (requires private release signing credentials)."
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.jsoup)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
