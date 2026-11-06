pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "mqtt"

// The DitchOoM siblings (buffer, socket, websocket) can be pointed at locally published snapshots
// without editing the catalog, e.g. `-PditchoomWebsocketVersion=2.1.0-SNAPSHOT` (or the
// ORG_GRADLE_PROJECT_ditchoomWebsocketVersion environment variable). CI's downstream canary uses
// this to build against each sibling's main branch; releases never set it.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            for ((alias, property) in listOf(
                "buffer" to "ditchoomBufferVersion",
                "socket" to "ditchoomSocketVersion",
                "websocket" to "ditchoomWebsocketVersion",
            )) {
                val override = providers.gradleProperty(property)
                if (override.isPresent) version(alias, override.get())
            }
        }
    }
}

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
