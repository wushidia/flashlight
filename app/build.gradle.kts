plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystore = System.getenv("FLASHLIGHT_KEYSTORE")
val releaseStorePassword = System.getenv("FLASHLIGHT_STORE_PASSWORD")
val releaseKeyPassword = System.getenv("FLASHLIGHT_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.custom.flashlight"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.custom.flashlight"
        minSdk = 24
        targetSdk = 37
        versionCode = 13
        versionName = "2.0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(requireNotNull(releaseKeystore))
            storePassword = releaseStorePassword
            keyAlias = System.getenv("FLASHLIGHT_KEY_ALIAS") ?: "flashlight"
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-blur:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:$miuix")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.animation:animation:1.12.1")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
    jvmArgs("-XX:ActiveProcessorCount=2")
}
