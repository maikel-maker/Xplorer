plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Signature : en CI, les secrets GitHub (XP_KEYSTORE_PATH, XP_STORE_PASSWORD, XP_KEY_ALIAS, XP_KEY_PASSWORD)
// sont prioritaires. Voir docs/SIGNATURE.md.
val ksPath = System.getenv("XP_KEYSTORE_PATH") ?: "${rootDir}/keystore/xplorer.jks"
val ksPass = System.getenv("XP_STORE_PASSWORD")
val ksAlias = System.getenv("XP_KEY_ALIAS") ?: "xplorer"
val ksKeyPass = System.getenv("XP_KEY_PASSWORD") ?: ksPass
val versionCodeEnv = System.getenv("XP_VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "be.maikel.xplorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.maikel.xplorer"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = "3.0.$versionCodeEnv"
    }

    signingConfigs {
        create("release") {
            if (ksPass != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPass != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
