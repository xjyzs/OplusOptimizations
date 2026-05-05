plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xjyzs.oplusoptimizations"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xjyzs.oplusoptimizations"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources. localeFilters+= listOf("zh")
    }
    signingConfigs {
        val hasSigningInfo = System.getenv("KEY_STORE_PASSWORD") != null &&
                System.getenv("KEY_ALIAS") != null &&
                System.getenv("KEY_PASSWORD") != null &&
                file("${project.rootDir}/keystore.jks").exists()
        if (hasSigningInfo) {
            create("release") {
                storeFile = file("${project.rootDir}/keystore.jks")
                storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                enableV1Signing=false
            }
        }
    }

    flavorDimensions += "abi"
    productFlavors {
        val signingConfig = if (signingConfigs.findByName("release") != null) {
            signingConfigs.getByName("release")
        } else {
            signingConfigs.getByName("debug")
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters.add("arm64-v8a") }
            this.signingConfig = signingConfig
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            packaging {
                resources {
                    excludes += setOf(
                        "DebugProbesKt.bin",
                        "kotlin-tooling-metadata.json",
                        "META-INF/**",
                        "kotlin/**"
                    )
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.collection.jvm)
    compileOnly(files("lib/XposedBridgeAPI-89.jar"))
}