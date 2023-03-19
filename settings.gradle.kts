import java.io.FileFilter

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
        val composeVersion = extra["compose.version"] as String
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("android").version(kotlinVersion)
        kotlin("native.cocoapods").version(kotlinVersion)
        id("com.android.application").version(androidGradleVersion)
        id("com.android.library").version(androidGradleVersion)
        id("io.codearte.nexus-staging").version(extra["nexus-staging.version"] as String)
        id("org.jlleitschuh.gradle.ktlint").version(extra["ktlint.version"] as String)
        id("app.cash.sqldelight").version(sqldelightVersion)
        id("org.jetbrains.compose").version(composeVersion)
    }
}

rootProject.name = "mqtt"

plugins {
    id("com.gradle.enterprise") version ("3.10.3")
}
gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishOnFailureIf(!System.getenv("CI").isNullOrEmpty())
    }
}

include("models-base", "models-v4", "models-v5", "mqtt-client", "monitor", "monitor:android", "monitor:common", "monitor:desktop", "monitor:web")
//file("monitor")
//    .listFiles(FileFilter { it.isDirectory })
//    ?.forEach { dir ->
//        include(dir.name)
//        project(dir).projectDir = dir
//    }
