plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.appsbyalok.echohunter"
    testNamespace = "com.appsbyalok.echohunter.test"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.appsbyalok.echohunter"
        minSdk = 16
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 28
        versionName = "0.10.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildFeatures {
        resValues = true
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "EchoHunterDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
//    splits {
//        abi {
//            isEnable = true
//            reset()
//            include( "arm64-v8a")
//            isUniversalApk = false
//        }
//    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Local Unit Tests
    testImplementation(libs.androidx.junit.ktx)

    // Android Instrumented Tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}