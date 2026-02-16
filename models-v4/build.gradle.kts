plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    alias(libs.plugins.sqldelight)
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
        linuxArm64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":models-base"))
            implementation(libs.buffer)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            compileOnly(libs.sqldelight.sqlite.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.browser)
            implementation(libs.kotlin.js)
        }
        nativeMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "com.ditchoom.mqtt3"
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

sqldelight {
    databases {
        create("Mqtt4") {
            packageName.set(group.toString())
        }
    }
}

// SQLDelight native linker fix
afterEvaluate {
    project.extensions
        .findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
        ?.let { kmpExt ->
            kmpExt.targets
                .filterIsInstance<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
                .forEach { target ->
                    target.binaries.forEach { binary ->
                        binary.linkerOpts("-lsqlite3")
                        if (target.konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
                            binary.linkerOpts(
                                "-L/usr/lib/x86_64-linux-gnu",
                                "-lpthread",
                                "-ldl",
                                "-lm",
                                "--allow-shlib-undefined",
                            )
                        } else if (target.konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_ARM64) {
                            binary.linkerOpts(
                                "-L/usr/lib/aarch64-linux-gnu",
                                "-lpthread",
                                "-ldl",
                                "-lm",
                                "--allow-shlib-undefined",
                            )
                        }
                    }
                }
        }
}
