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
            storeFile = file("release.keystore")
            // এখানে আপনার আসল পাসওয়ার্ড এবং অ্যালিয়াস বসান (কোটেশন মার্ক "" এর ভেতরে)
            storePassword = "LottoGst@#12345676" 
            keyAlias = "WebApp_key"
            keyPassword = "LottoGst@#12345676"
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
