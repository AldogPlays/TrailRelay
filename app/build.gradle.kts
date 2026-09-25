plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.trailrelay.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.trailrelay.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "TrailRelay Dev")
        }
        release {
            isDebuggable = false
            // Sign locally with Android Studio's Generate Signed Bundle/APK wizard.
            // Keep signing credentials outside the project; CLI release builds are unsigned.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.maplibre)
    implementation(libs.maplibre.turf)
    testImplementation(libs.junit)
    // Android supplies org.json at runtime; JVM tests need its implementation.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
