import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tonbo2339.yomikura"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 115
        versionName = "1.2.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // 版 (flavor): oss = 公開版のヨミクラ (src/oss)。共通のコードは src/main。
    // 版ごとに違う窓口は Edition.kt (その版のフォルダ src/<版> に、同じ名前で置く)
    flavorDimensions += "edition"

    // リリース署名: リポジトリの外にある keystore.properties (storeFile / storePassword / keyAlias / keyPassword) を使う。
    // 鍵の場所は版ごとに -P / 環境変数で変えられる。何も指定しないときの既定は、公開版 (oss) はリポジトリ直下の keystore.properties (git に入らない)。ほかの版の既定は、下の版の定義を見る。
    // 鍵の設定が見つからないとき: その版のリリース (assembleOssRelease など) を作ろうとしたら失敗にする (debug 鍵の APK を配ってしまう事故を防ぐ。確認だけなら -Pyomikura.allowDebugSigning=true)。
    // それ以外 (debug・テスト・版を指定しない assembleRelease) は、debug 鍵で署名し、リリースのときは警告を出す。
    // buildType の signingConfig は flavor のものより優先されるので、リリース鍵は flavor に付ける (debug は debug 鍵のまま)
    val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    // 旧名の設定 (legacyProp / legacyEnv) も、新しい名前が無いときは受け付ける (手元のスクリプトや環境変数を壊さないため)
    fun signingFile(prop: String, env: String, default: String, legacyProp: String? = null, legacyEnv: String? = null): File =
        (findProperty(prop) as String? ?: System.getenv(env)
            ?: legacyProp?.let { findProperty(it) as String? } ?: legacyEnv?.let { System.getenv(it) })
            ?.let { file(it) } ?: rootProject.file(default)
    // そのリリース (name = 版 + Release。assembleOssRelease なら ossRelease) を作ろうとしているか
    fun building(name: String) = gradle.startParameter.taskNames.any { it.contains(name, ignoreCase = true) }
    fun releaseSigning(name: String, props: File): com.android.build.api.dsl.ApkSigningConfig? {
        if (!props.isFile) {
            // 鍵が無いまま配るリリースを作ると、debug 鍵で署名されてしまい、次の版を「更新」として入れられなくなる。だから、作ろうとしたときは失敗にする。
            // 手元の確認で debug 鍵のリリースが欲しいときだけ、-Pyomikura.allowDebugSigning=true か環境変数 YOMIKURA_ALLOW_DEBUG_SIGNING=true で通す
            val allow = (findProperty("yomikura.allowDebugSigning") as String? ?: System.getenv("YOMIKURA_ALLOW_DEBUG_SIGNING")) == "true"
            if (building(name) && !allow) {
                throw GradleException("署名の設定 ${props.path} が見つかりません。リリース ($name) を作れません (debug 鍵で署名した APK は、配ると更新できなくなります)。" +
                    "鍵の設定 (keystore.properties) を用意するか、確認だけなら -Pyomikura.allowDebugSigning=true を付けてください")
            }
            if (releaseRequested) {
                logger.warn("WARNING: 署名の設定 ${props.path} が見つかりません。リリース ($name) は debug 鍵で署名されます (配布には使わないでください)")
            }
            return null
        }
        return signingConfigs.create(name) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            fun need(key: String): String = requireNotNull(p.getProperty(key)?.takeIf { it.isNotBlank() }) {
                "${props.path} に $key がありません (storeFile / storePassword / keyAlias / keyPassword が必要です)"
            }
            val store = File(need("storeFile"))
            storeFile = if (store.isAbsolute) store else File(props.parentFile, store.path)
            storePassword = need("storePassword")
            keyAlias = need("keyAlias")
            keyPassword = need("keyPassword")
        }
    }

    // アプリ名 (ランチャーの名前・設定画面の表示) は、版ごとに 1 か所だけ決める
    fun com.android.build.api.dsl.ProductFlavor.appName(name: String) {
        buildConfigField("String", "APP_NAME", "\"$name\"")
        manifestPlaceholders["appName"] = name
    }

    productFlavors {
        create("oss") {
            dimension = "edition"
            applicationId = "com.tonbo2339.yomikura"
            appName("ヨミクラ")
            // -Pyomikura.signing=<path> か環境変数 YOMIKURA_SIGNING、無ければ リポジトリ直下の keystore.properties
            signingConfig = releaseSigning("ossRelease", signingFile("yomikura.signing", "YOMIKURA_SIGNING", "keystore.properties"))
                ?: signingConfigs.getByName("debug")
        }
    }

    buildTypes {
        // debug 版は別のアプリとして入る (パッケージ名に .debug)。検証用の端末にリリース版と並べて入れ、CDP で調べられるように。
        // データ・ログイン・Cookie はリリース版と別。アプリ内の更新はパッケージ名が違うので使えない (使わない)
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabelSuffix"] = " (debug)"
        }
        release {
            manifestPlaceholders["appLabelSuffix"] = ""
            // コードとリソースの縮小 (R8)。JS から呼ぶ @JavascriptInterface と、マニフェストが名前で指す物は proguard-rules.pro で残す
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // 単体テスト (app/src/test)。Android の org.json はテストでは中身が無いので、本物を入れる
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
