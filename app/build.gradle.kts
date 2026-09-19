plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val veltrixBuildSha = listOf("VELTRIX_GIT_SHA", "CIRCLE_SHA1", "GITHUB_SHA")
    .asSequence()
    .mapNotNull { providers.environmentVariable(it).orNull?.trim()?.lowercase() }
    .firstOrNull { it.matches(Regex("[0-9a-f]{40}")) }
    .orEmpty()

val veltrixBackendUrl = providers.environmentVariable("VELTRIX_BACKEND_URL")
    .orNull
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotEmpty() }
    ?: "https://veltrix-magicar-api.onrender.com"
require(veltrixBackendUrl.startsWith("https://")) { "VELTRIX_BACKEND_URL must use HTTPS" }
require(!veltrixBackendUrl.contains('"')) { "VELTRIX_BACKEND_URL contains an invalid quote" }

val releaseStorePath = providers.environmentVariable("VELTRIX_ANDROID_KEYSTORE_PATH").orNull?.trim().orEmpty()
val releaseStorePassword = providers.environmentVariable("VELTRIX_ANDROID_KEYSTORE_PASSWORD").orNull.orEmpty()
val releaseKeyAlias = providers.environmentVariable("VELTRIX_ANDROID_KEY_ALIAS").orNull.orEmpty()
val releaseKeyPassword = providers.environmentVariable("VELTRIX_ANDROID_KEY_PASSWORD").orNull.orEmpty()
val releaseSigningValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all(String::isNotBlank)
val requireReleaseSigning = providers.environmentVariable("VELTRIX_REQUIRE_RELEASE_SIGNING")
    .orNull
    ?.trim()
    ?.equals("true", ignoreCase = true) == true
require(releaseSigningValues.none(String::isNotBlank) || releaseSigningConfigured) {
    "Release signing requires VELTRIX_ANDROID_KEYSTORE_PATH, VELTRIX_ANDROID_KEYSTORE_PASSWORD, VELTRIX_ANDROID_KEY_ALIAS and VELTRIX_ANDROID_KEY_PASSWORD together"
}
require(!requireReleaseSigning || releaseSigningConfigured) {
    "Production release signing is required but the complete Android signing configuration is unavailable"
}
if (requireReleaseSigning) {
    val releaseStoreFile = file(releaseStorePath).canonicalFile
    require(releaseStoreFile.isFile) {
        "Production release signing keystore is unavailable"
    }
    val checkoutRoot = rootProject.projectDir.canonicalFile.toPath()
    require(!releaseStoreFile.toPath().startsWith(checkoutRoot)) {
        "Production release signing keystore must stay outside the source checkout"
    }
}

android {
    namespace = "com.veltrix.ultron"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veltrix.magicar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-rc1"
        buildConfigField("String", "VELTRIX_BUILD_SHA", "\"$veltrixBuildSha\"")
        buildConfigField("String", "VELTRIX_BACKEND_URL", "\"$veltrixBackendUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("veltrixRelease") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("veltrixRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline wake-word runtime. Model assets are provisioned by scripts/prepare_magicar_kws.sh.
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.10")

    testImplementation("junit:junit:4.13.2")
}
