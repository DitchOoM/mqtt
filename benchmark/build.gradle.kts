plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.allopen") version libs.versions.kotlin.get()
    id("org.jetbrains.kotlinx.benchmark") version libs.versions.kotlinxBenchmark.get()
}

// Required for JMH @State classes to be open
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

repositories {
    google()
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(21)
    jvm()
    js {
        nodejs()
    }

    if (hostOs == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64) {
        linuxX64()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":models-base"))
            implementation(project(":models-v4"))
            implementation(project(":models-v5"))
            implementation(project(":mqtt-client"))
            implementation(libs.buffer)
            implementation(libs.buffer.flow)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("js")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1000
            iterationTimeUnit = "ms"
        }
        register("quick") {
            warmups = 1
            iterations = 1
            iterationTime = 100
            iterationTimeUnit = "ms"
        }
    }
}
