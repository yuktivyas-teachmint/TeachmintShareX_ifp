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

val computedVersionName: String = if (System.getenv("CI") == "true") {
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
        minSdk = libs.versions.android.minSdk.get().toInt()
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)

    // composeApp's debug/release API classes are currently published as an
    // android-classes-jar containing only R stubs. Add the generated full jar
    // for Kotlin compile classpath so androidApp can resolve shared symbols.
    debugCompileOnly(
        files(
            "${rootProject.projectDir}/composeApp/build/intermediates/full_jar/debug/createFullJarDebug/full.jar"
        ).builtBy(":composeApp:createFullJarDebug")
    )
    releaseCompileOnly(
        files(
            "${rootProject.projectDir}/composeApp/build/intermediates/full_jar/release/createFullJarRelease/full.jar"
        ).builtBy(":composeApp:createFullJarRelease")
    )
}
