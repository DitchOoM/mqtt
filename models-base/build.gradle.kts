plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    signing
    id("com.ditchoom.version")
    id("com.ditchoom.module")
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

kotlin {
    jvmToolchain(21)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    js {
        browser()
        nodejs()
    }

    if (hostOs.family.isAppleFamily) {
        macosX64()
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        watchosArm64()
        watchosSimulatorArm64()
        watchosX64()
    }

    if (hostOs == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
        linuxX64()
        // linuxArm64() // disabled until buffer publishes linuxArm64 SNAPSHOT
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.buffer)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 19
    }
    namespace = "com.ditchoom.mqtt"
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}
