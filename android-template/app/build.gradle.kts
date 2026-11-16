plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.websitebuilder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.websitebuilder.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // এখানে সরাসরি ফাইলের নাম উল্লেখ করা হলো যাতে কোনো কনফিউশন না থাকে
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
