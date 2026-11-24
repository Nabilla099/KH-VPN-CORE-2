plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing: pakai env dari GitHub Actions (secrets) kalau ada, kalau tidak pakai keystore bawaan repo
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val abiList = env("KH_ABIS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val ksFile = env("KH_KEYSTORE_FILE")?.let { file(it) } ?: file("khvpn-default.jks")
val ksPass = env("KH_KEYSTORE_PASSWORD") ?: "khvpn-default"
val ksAlias = env("KH_KEY_ALIAS") ?: "khvpn"
val ksKeyPass = env("KH_KEY_PASSWORD") ?: ksPass

android {
    namespace = "com.khvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khvpn.app"
        minSdk = 23            // Android 6.0
        targetSdk = 35
        versionCode = (env("KH_VERSION_CODE") ?: "23").toInt()
        versionName = env("KH_VERSION_NAME") ?: "2.2.0"
    }

    signingConfigs {
        create("kh") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }

    // APK per ABI (kecil, hemat penyimpanan) + 1 APK universal
    splits {
        abi {
            isEnable = true
            reset()
            include(*abiList.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("kh")
        }
        debug {
            signingConfig = signingConfigs.getByName("kh")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        // WAJIB: libdnstt.so & libtun2socks.so adalah executable yang dijalankan dari nativeLibraryDir,
        // jadi harus diekstrak ke disk (bukan di-mmap dari APK).
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }

    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navsuite)
    implementation(libs.compose.icons.extended)
    implementation(libs.coroutines.android)
    implementation(libs.sshlib)
}
