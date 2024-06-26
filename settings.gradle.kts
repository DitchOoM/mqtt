pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String
        val androidGradleVersion = extra["agp.version"] as String
        val sqldelightVersion = extra["sqldelight.version"] as String
//        val composePluginVersion = extra["compose.plugin.version"] as String
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("android").version(kotlinVersion)
        kotlin("cocoapods").version(kotlinVersion)
        kotlin("native.cocoapods").version(kotlinVersion)
        id("com.android.application").version(androidGradleVersion)
        id("com.android.library").version(androidGradleVersion)
        id("io.codearte.nexus-staging").version(extra["nexus-staging.version"] as String)
        id("org.jlleitschuh.gradle.ktlint").version(extra["ktlint.version"] as String)
        id("app.cash.sqldelight").version(sqldelightVersion)
//        id("org.jetbrains.compose").version(composePluginVersion)
    }
}

rootProject.name = "mqtt"

plugins {
    id("com.gradle.develocity") version ("3.17.3")
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
