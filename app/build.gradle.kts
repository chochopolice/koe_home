import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.koehome"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.koehome"
        minSdk = 28          // Android 9 以上
        targetSdk = 36      // Google Play の要件 (2026年8月31日以降は 36 以上)
        versionCode = 15
        versionName = "0.15"
    }

    // どこでビルドしても同じ署名になるよう、同梱のデバッグ用キーを使う。
    // (署名が変わると上書きインストールできず、入れ直しになるため)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
