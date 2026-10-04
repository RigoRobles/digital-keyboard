plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.btkeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.btkeyboard"
        // 28 is the hard floor: BluetoothHidDevice (HID Device profile) was added in API 28.
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
