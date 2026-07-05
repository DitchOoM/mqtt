plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    alias(libs.plugins.ksp)
    alias(libs.plugins.buffer.codec.schema)
    signing
    id("com.ditchoom.version")
    id("com.ditchoom.module")
}

// Wire-format snapshot gate — see models-base/build.gradle.kts. MQTT's wire format is fixed by the
// v5.0 spec, so any breaking drift fails the build. Accept intentional changes with
// `./gradlew updateCodecSchema` + commit src/codecSchema/codec-schema.txt.
codecSchema {
    failOnBreaking.set(true)
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

kotlin {
    jvmToolchain(21)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm {
        // jazzer-junit (JVM coverage-guided fuzzing, see fuzz targets in src/jvmTest) requires
        // the JUnit Platform. kotlin("test") resolves to kotlin-test-junit5 accordingly.
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
        nodejs {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
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
            implementation(libs.buffer.codec)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.jazzer.junit)
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.browser)
            implementation(libs.kotlin.js)
        }
    }
}

// KSP: generate codecs for commonMain (visible to all targets)
dependencies {
    add("kspCommonMainMetadata", libs.buffer.codec.processor)
}

// Wire KSP commonMain output into each target's source set
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// Ensure KSP runs before compilation for all targets
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
    }
    namespace = "com.ditchoom.mqtt5"
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Deterministic + Jazzer fuzz tests are opt-in so default test runs stay fast; mirrors the
// integrationTests gating in mqtt-client/build.gradle.kts.
// Run with: ./gradlew :models-v5:jvmTest -PfuzzTests
val fuzzTestPatterns =
    listOf(
        "com.ditchoom.mqtt5.controlpacket.fuzz.ControlPacketV5FuzzTest",
        "com.ditchoom.mqtt5.controlpacket.fuzz.ControlPacketV5JazzerFuzzTest",
    )
val runFuzzTests = project.hasProperty("fuzzTests")

tasks.withType<Test>().configureEach {
    if (!runFuzzTests) {
        filter {
            fuzzTestPatterns.forEach { excludeTestsMatching(it) }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    if (!runFuzzTests) {
        fuzzTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (!runFuzzTests) {
        fuzzTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
}

// Gradle 8.14 strict-mode: sourcesJar / ktlint / dokka tasks consume KSP-generated sources; declare the dep explicitly.
tasks
    .matching {
        it.name.endsWith("SourcesJar") ||
            it.name == "sourcesJar" ||
            it.name == "runKtlintCheckOverCommonMainSourceSet" ||
            it.name == "runKtlintFormatOverCommonMainSourceSet" ||
            it.name.startsWith("dokkaGenerate")
    }.configureEach { dependsOn("kspCommonMainKotlinMetadata") }
