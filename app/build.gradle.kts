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
        versionCode = 19
        versionName = "0.19"
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

        // 公開用 (Google Play へのアップロード鍵)。
        // 鍵ファイルとパスワードはリポジトリに置かず、環境変数で受け取る。
        // 環境変数が無いとき (ふだんのデバッグビルド) は、この設定自体を作らない。
        val uploadKeystore = System.getenv("KOEHOME_KEYSTORE")
        if (!uploadKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("KOEHOME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KOEHOME_KEY_ALIAS")
                keyPassword = System.getenv("KOEHOME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        // 公開用ビルドを lint の指摘だけで止めない (指摘はレポートで確認する)
        checkReleaseBuilds = false
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
