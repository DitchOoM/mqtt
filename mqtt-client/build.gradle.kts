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
        browser {
            testTask {
                useMocha {
                    timeout = "180s"
                }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "180s"
                }
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
        // linuxArm64() // disabled until buffer publishes linuxArm64 SNAPSHOT
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":models-base"))
            implementation(project(":models-v4"))
            implementation(project(":models-v5"))
            implementation(libs.buffer)
            implementation(libs.buffer.codec)
            implementation(libs.buffer.flow)
            implementation(libs.socket)
            implementation(libs.websocket)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":models-v4"))
            implementation(project(":models-v5"))
            implementation(libs.socket)
            implementation(libs.websocket)
        }

        jsMain.dependencies {
            implementation(libs.kotlin.js)
        }

        androidMain.dependencies {
            implementation(libs.androidx.startup)
        }

        val androidInstrumentedTest by getting
        androidInstrumentedTest.dependsOn(commonTest.get())
        androidInstrumentedTest.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.core.ktx)
        }

        val jvmTest by getting
        jvmTest.dependencies {
            implementation(libs.testcontainers)
        }
    }
}

// Integration tests require a running broker (local Mosquitto or public brokers).
// Run with: ./gradlew :mqtt-client:jvmTest -PintegrationTests
val integrationTestPatterns =
    listOf(
        "com.ditchoom.mqtt.client.net.EndToEndBenchmark",
        "com.ditchoom.mqtt.client.net.EndToEndBrokerBenchmarkTest",
        "com.ditchoom.mqtt.client.net.MqttSocketSessionTest",
        "com.ditchoom.mqtt.client.net.MqttClientTest",
        "com.ditchoom.mqtt.client.net.PublicBrokerValidationTest",
    )

val runIntegrationTests = project.hasProperty("integrationTests")

// Filter JVM tests
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
    jvmArgs("-XX:MaxDirectMemorySize=2g")
    if (!runIntegrationTests) {
        filter {
            integrationTestPatterns.forEach { excludeTestsMatching(it) }
        }
    }
}

// Filter Kotlin/Native tests
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    if (!runIntegrationTests) {
        integrationTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
}

// Filter Kotlin/JS tests
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (!runIntegrationTests) {
        integrationTestPatterns.forEach { this.filter.excludeTestsMatching(it) }
    }
    // Benchmark tests are too slow for JS single-threaded event loop
    this.filter.excludeTestsMatching("com.ditchoom.mqtt.client.net.EndToEndBrokerBenchmarkTest")
    this.filter.excludeTestsMatching("com.ditchoom.mqtt.client.net.EndToEndBenchmark")
}

android {
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    buildFeatures {
        aidl = true
    }
    defaultConfig {
        minSdk = 21
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Mirror the JVM/Native/JS `runIntegrationTests` filter on Android instrumented tests.
        // Without this, connectedDebugAndroidTest runs the broker-dependent suite by default and
        // every test that talks to a broker times out at 60s — see [[mqtt_client_integration_test_flakiness]].
        // Opt in with -PintegrationTests (and -PuseMosquittoContainer=true to actually start a broker).
        if (!runIntegrationTests) {
            testInstrumentationRunnerArguments["notClass"] = integrationTestPatterns.joinToString(",")
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
    lint {
        disable += "EnsureInitializerMetadata"
    }
    namespace = "com.ditchoom.mqtt.client"
}

// Mosquitto container for Android instrumented tests.
// Run with: ./gradlew :mqtt-client:connectedDebugAndroidTest -PuseMosquittoContainer=true
if (project.hasProperty("useMosquittoContainer")) {
    val mosquittoConf = "${project.projectDir}/src/androidInstrumentedTest/resources/mosquitto.conf"
    val startMosquitto by tasks.registering(Exec::class) {
        group = "testing"
        description = "Start Mosquitto Docker container for instrumented tests"
        commandLine(
            "docker",
            "run",
            "-d",
            "--rm",
            "-p",
            "1883:1883",
            "-p",
            "8080:8080",
            "--name",
            "mqtt-it",
            "-v",
            "$mosquittoConf:/mosquitto/config/mosquitto.conf",
            "eclipse-mosquitto:2",
        )
    }
    val stopMosquitto by tasks.registering(Exec::class) {
        group = "testing"
        description = "Stop Mosquitto Docker container"
        commandLine("docker", "stop", "mqtt-it")
        isIgnoreExitValue = true
    }
    afterEvaluate {
        tasks.named("connectedDebugAndroidTest") {
            dependsOn(startMosquitto)
            finalizedBy(stopMosquitto)
        }
    }
}

// SQLDelight native linker fix (transitive dependency via models-v4/v5)
afterEvaluate {
    project.extensions
        .findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
        ?.let { kmpExt ->
            kmpExt.targets
                .filterIsInstance<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
                .forEach { target ->
                    target.binaries.forEach { binary ->
                        if (target.konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
                            binary.linkerOpts(
                                "-L/usr/lib/x86_64-linux-gnu",
                                "-l:libsqlite3.a",
                                "-lpthread",
                                "-ldl",
                                "-lm",
                            )
                        } else if (target.konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_ARM64) {
                            binary.linkerOpts(
                                "-L/usr/lib/aarch64-linux-gnu",
                                "-l:libsqlite3.a",
                                "-lpthread",
                                "-ldl",
                                "-lm",
                            )
                        } else {
                            binary.linkerOpts("-lsqlite3")
                        }
                    }
                }
        }
}
