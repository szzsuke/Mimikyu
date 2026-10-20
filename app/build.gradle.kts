import java.util.Properties

plugins {
    alias(libs.plugins.google.services)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose_compiler/metrics")
    reportsDestination = layout.buildDirectory.dir("compose_compiler/reports")
}

// 签名材料一律来自仓库外：本地读 local.properties，CI 读同名环境变量。
// 四项缺任意一项即视为未配置，release 退回调试签名，便于本地随时打包验证。
val keystoreProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")

val supabaseLyricsUrl = (keystoreProps.getProperty("SUPABASE_LYRICS_BASE_URL") ?: System.getenv("SUPABASE_LYRICS_BASE_URL"))?.takeIf { it.isNotBlank() }
    ?: ""
val supabaseAnonKey = (keystoreProps.getProperty("SUPABASE_ANON_KEY") ?: System.getenv("SUPABASE_ANON_KEY"))?.takeIf { it.isNotBlank() }
    ?: ""

val hasReleaseSigning = releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null &&
        rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.lin0721.linmusic"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.szzsuke.mimikyu"
        minSdk = 26
        targetSdk = 36
        // CI 打 tag 发布时通过 -PreleaseVersionCode/-PreleaseVersionName 覆盖，本地构建保持默认值
        versionCode = (project.findProperty("releaseVersionCode") as String?)?.toIntOrNull() ?: 4
        versionName = (project.findProperty("releaseVersionName") as String?) ?: "1.3.0"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AUDIOMETA_API_KEY", "\"\"")
        buildConfigField("String", "SUPABASE_LYRICS_BASE_URL", "\"$supabaseLyricsUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // 未配置签名时沿用调试签名，保证 assembleRelease 始终可产出可安装包
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    testOptions {
        unitTests {
            // AppLogger 内部调用 android.util.Log，纯 JVM 单测下未做该 mock 会直接抛异常
            isReturnDefaultValues = true
        }
    }
}

// Output APK filename: Mimikyu-v{versionName}-{buildType}.apk
base {
    archivesName.set("Mimikyu-v${android.defaultConfig.versionName}")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // DI - Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Image loading - Coil
    implementation(libs.coil.compose)

    // Media3 & Media
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.taglib)
    implementation(libs.haze)
    implementation(libs.android.image.cropper)
    implementation(libs.zxing.core)
    implementation(libs.bouncycastle)
    implementation(project(":innertube"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.timber)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Import the BoM for the Firebase platform
    implementation(platform("com.google.firebase:firebase-bom:35.0.1"))

    // Add the dependency for the Firebase Authentication library
    implementation("com.google.firebase:firebase-auth")

    // Firebase App Check (Play Integrity for release, Debug provider for debug builds)
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // Also add the dependency for the Google Play services library and specify its version
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    coreLibraryDesugaring(libs.desugaring)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20230227")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
    enabled = false
}
