import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing details live outside the repo. Without keystore.properties the
// release build simply stays unsigned instead of failing.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.pankaj.mlbbdraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pankaj.mlbbdraft"
        // 26 is the floor for TYPE_APPLICATION_OVERLAY, which Phase 1 needs.
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5-auto-catalogue-refresh"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed when keystore.properties is present, otherwise unsigned —
            // an unsigned APK is what Android reports as "package appears to be invalid".
            signingConfig = signingConfigs.findByName("release")
            // Left off deliberately: R8 has nothing to gain here yet, and shrinking
            // adds a failure mode between you and a working install.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    // Explicit so the Kotlin source layout does not depend on plugin defaults.
    sourceSets.getByName("main") {
        java.srcDirs("src/main/java", "src/main/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.mlkit.text.recognition)
    // Local image embeddings compare the red Equipment item grid with bundled non-spell templates.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
