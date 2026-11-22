plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.kozin.ytaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.kozin.ytaudio"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    // 빌드할 때마다 서명이 바뀌면 덮어쓰기 설치가 안 되므로 고정 키를 쓴다.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("app-signing.jks")
            storePassword = "ytaudio-sign"
            keyAlias = "ytaudio"
            keyPassword = "ytaudio-sign"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // yt-dlp·ffmpeg 실행 파일은 압축을 풀어 설치해야 실행된다.
    packaging { jniLibs { useLegacyPackaging = true } }

    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
