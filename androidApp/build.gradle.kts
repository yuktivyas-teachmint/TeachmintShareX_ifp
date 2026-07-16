import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidApplication)
    kotlin("android")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

// Apply google-services and crashlytics only when google-services.json is present (CI generates it from secrets).
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
    apply(plugin = libs.plugins.firebaseCrashlytics.get().pluginId)
}

val versionSchema = "release-v"
val defaultVersionName = "1.0.0"
val baseVersionCode = 1000

// Local (non-CI) builds otherwise stamp defaultVersionName, so an OTA APK built
// locally would report a stale version after installing — pass the real version
// with -PappVersionName=1.0.1 when building an APK for OTA publication.
val computedVersionName: String = findProperty("appVersionName")?.toString()?.takeIf { it.isNotBlank() }
    ?: if (System.getenv("CI") == "true") {
        runCatching {
            val fromEnv = System.getenv("GITHUB_REF_NAME")?.trim().orEmpty()
            val branch = if (fromEnv.isNotEmpty()) {
                fromEnv
            } else {
                val stdout = ByteArrayOutputStream()
                exec {
                    commandLine("sh", "-c", "git branch --show-current | tail -n 1")
                    standardOutput = stdout
                }
                stdout.toString().trim()
            }
            when {
                branch.startsWith(versionSchema) -> branch.removePrefix(versionSchema)
                branch.isNotEmpty() -> branch
                else -> defaultVersionName
            }
        }.getOrElse {
            logger.warn("Failed to resolve branch for versionName: ${it.message}")
            defaultVersionName
        }
    } else {
        defaultVersionName
    }

val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val computedVersionCode: Int = baseVersionCode + buildNumber

logger.lifecycle("Version Name: $computedVersionName")
logger.lifecycle("Version Code: $computedVersionCode")

android {
    namespace = "com.teachmint.sharex.androidapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.teachmint.shareX.ifp"
        // OTA library (com.teachmint.ota) AAR requires minSdk 29; IFP panels all run Android 10+.
        minSdk = 29
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = computedVersionCode
        versionName = computedVersionName
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    signingConfigs {
        create("nonstarSigning") {
            storeFile = file("platform_3576_14.jks")
            storePassword = "ktc123123"
            keyAlias = "skg"
            keyPassword = "ktc123123"
        }
        create("starSigning") {
            storeFile = file("teachmint1_platform.jks")
            storePassword = "123456"
            keyAlias = "teachmint"
            keyPassword = "123456"
        }
        create("langoSigning") {
            storeFile = file("mtk_edla.jks")
            storePassword = "android"
            keyAlias = "android"
            keyPassword = "android"
        }
        create("cvteSigning") {
            storeFile = file("teachmint1_platform.jks")
            storePassword = "123456"
            keyAlias = "teachmint"
            keyPassword = "123456"
        }
    }

    flavorDimensions += "platform"
    productFlavors {
        create("star") {
            dimension = "platform"
        }
        create("nonstar") {
            dimension = "platform"
        }
        create("lango") {
            dimension = "platform"
        }
        create("cvte") {
            dimension = "platform"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

// Capture signing configs reference (same pattern as chakra)
val nonstarSigning = android.signingConfigs.getByName("nonstarSigning")
val starSigning = android.signingConfigs.getByName("starSigning")
val langoSigning = android.signingConfigs.getByName("langoSigning")
val cvteSigning = android.signingConfigs.getByName("cvteSigning")

// Apply each platform's signing key to its flavor (debug and release)
androidComponents {
    onVariants { variant ->
        when (variant.flavorName) {
            "nonstar" -> variant.signingConfig.setConfig(nonstarSigning)
            "star" -> variant.signingConfig.setConfig(starSigning)
            "lango" -> variant.signingConfig.setConfig(langoSigning)
            "cvte" -> variant.signingConfig.setConfig(cvteSigning)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val otaVer = findProperty("otaVersion")?.toString() ?: "1.0.4-beta02"

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    // Teachmint OTA update library (pulls in WorkManager, DataStore, OkHttp, Gson)
    implementation("com.teachmint.ota:ota:$otaVer")
    // Vendor SDKs for reading the panel serial number (see DeviceSerialResolver):
    // XbhSdk = Lango/XBH boards, client-sdk + binderhttpd = CVTE UDI service.
    implementation(files("lib/XbhSdk.jar"))
    implementation(files("lib/client-sdk-1.0.25.aar"))
    implementation(files("lib/binderhttpd-0.0.17.aar"))
    // UdiSdk hands back an OkHttpClient.Builder, so OkHttp must be on the compile classpath.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
