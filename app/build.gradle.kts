plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.zhou.floatingtranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhou.floatingtranslator"
        minSdk = 33
        targetSdk = 35
        versionCode = 18
        versionName = "0.5.8"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

    // High-accuracy optional offline ASR runtime. Model packs are downloaded separately.
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.8") {
        // The Android AAR already contains the Java API classes. The transitive JVM
        // helper jar duplicates those classes and must not be packaged into Android.
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    implementation("org.apache.commons:commons-compress:1.27.1")
}
