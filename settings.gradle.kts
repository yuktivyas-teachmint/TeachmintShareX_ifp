rootProject.name = "TeachmintShareX"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Teachmint private Maven repo (hosts com.teachmint.ota)
        maven {
            val repoUrl: String by settings
            val repoUser: String by settings
            val repoPassword: String by settings
            url = uri(repoUrl)
            credentials {
                username = repoUser
                password = repoPassword
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":androidApp")
