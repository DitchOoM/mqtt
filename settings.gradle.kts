pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "mqtt"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version ("4.3")
}

develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}

include(
    "models-base",
    "models-v4",
    "models-v5",
    "mqtt-client",
)
