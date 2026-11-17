plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "app.prompter"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.prompter"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
    }
    // Fixed key: Android only accepts an update if it is signed with the same key as the installed app.
    signingConfigs {
        create("shared") {
            storeFile = file("prompter.keystore")
            storePassword = "prompter123"
            keyAlias = "prompter"
            keyPassword = "prompter123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
