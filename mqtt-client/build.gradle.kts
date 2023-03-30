plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    id("com.android.library")
    id("io.codearte.nexus-staging")
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint")
}

val libraryVersionPrefix: String by project
val publishedGroupId: String by project
group = publishedGroupId
version = "${libraryVersionPrefix}0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")) - 2)}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}

repositories {
    mavenCentral()
    google()
    mavenLocal()
}

kotlin {
    android {
        publishLibraryVariants("release")
    }
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js(BOTH) {
        browser()
        nodejs {
            testTask {
                useMocha {
                    timeout = "10s"
                }
            }
        }
    }
    macosArm64()
    macosX64()
    ios()
    iosSimulatorArm64()
    tasks.getByName<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        deviceId = "iPhone 14"
    }

//    watchos()
//    tvos()
    cocoapods {
        ios.deploymentTarget = "13.0"
        osx.deploymentTarget = "11.0"
        watchos.deploymentTarget = "6.0"
        tvos.deploymentTarget = "13.0"
        pod("SocketWrapper") {
            source = git("https://github.com/DitchOoM/apple-socket-wrapper.git") {
                tag = "0.1.3"
            }
        }
    }
    sourceSets {
        val bufferVersion = extra["buffer.version"] as String
        val coroutinesVersion = extra["coroutines.version"] as String
        val socketVersion = extra["socket.version"] as String
        val websocketVersion = extra["websocket.version"] as String
        val atomicfuVersion = extra["atomicfu.version"] as String
        val commonMain by getting {
            dependencies {
                api("com.ditchoom:buffer:$bufferVersion") {
                    version { strictly(bufferVersion) }
                }
                compileOnly(project(":models-v4"))
                compileOnly(project(":models-v5"))
                implementation("com.ditchoom:socket:$socketVersion")
                implementation("com.ditchoom:websocket:$websocketVersion")
                api(project(":models-base"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":models-v4"))
                implementation(project(":models-v5"))
            }
        }
        val commonJvmTest by sourceSets.creating {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }

        val jvmMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
        }

        val jvmTest by getting {
            dependsOn(commonJvmTest)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:$coroutinesVersion")
            }
        }

        val androidMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
            dependsOn(commonMain)
            dependsOn(jvmMain)
            dependencies {
                implementation("androidx.startup:startup-runtime:1.1.1")
            }
        }
        val androidTest by getting {
            dependsOn(commonTest)
            dependsOn(commonJvmTest)
        }

        val androidAndroidTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
            kotlin.srcDir("src/commonTest/kotlin")
            dependsOn(commonJvmTest)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
                implementation("androidx.test:runner:1.5.2")
                implementation("androidx.test:rules:1.5.0")
                implementation("androidx.test:core-ktx:1.5.0")
                implementation("androidx.test:monitor:1.6.1")
            }
        }

        val jsMain by getting {
            dependsOn(commonMain)
            dependencies {

                implementation("org.jetbrains.kotlin-wrappers:kotlin-js:1.0.0-pre.521")
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        val macosX64Test by getting
        val macosArm64Test by getting
        val iosTest by getting
        val iosSimulatorArm64Test by getting
//        val watchosTest by getting
//        val tvosTest by getting
//
        val appleTest by sourceSets.creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/appleTest/kotlin")
            macosX64Test.dependsOn(this)
            macosArm64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
//            watchosTest.dependsOn(this)
//            tvosTest.dependsOn(this)
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["androidTest"].manifest.srcFile("src/androidAndroidTest/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    lint {
        abortOnError = false
    }
    namespace = "com.ditchoom.mqtt.client"
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

System.getenv("GITHUB_REPOSITORY")?.let {
    signing {
        useInMemoryPgpKeys(
            "56F1A973",
            System.getenv("GPG_SECRET"),
            System.getenv("GPG_SIGNING_PASSWORD")
        )
        sign(publishing.publications)
    }

    val ossUser = System.getenv("SONATYPE_NEXUS_USERNAME")
    val ossPassword = System.getenv("SONATYPE_NEXUS_PASSWORD")

    val publishedGroupId: String by project
    val libraryName: String by project
    val libraryDescription: String by project
    val siteUrl: String by project
    val gitUrl: String by project
    val licenseName: String by project
    val licenseUrl: String by project
    val developerOrg: String by project
    val developerName: String by project
    val developerEmail: String by project
    val developerId: String by project

    project.group = publishedGroupId
    project.version = libraryVersion

    publishing {
        publications.withType(MavenPublication::class) {
            groupId = publishedGroupId
            version = libraryVersion

            artifact(tasks["javadocJar"])

            pom {
                name.set(libraryName)
                description.set(libraryDescription)
                url.set(siteUrl)

                licenses {
                    license {
                        name.set(licenseName)
                        url.set(licenseUrl)
                    }
                }
                developers {
                    developer {
                        id.set(developerId)
                        name.set(developerName)
                        email.set(developerEmail)
                    }
                }
                organization {
                    name.set(developerOrg)
                }
                scm {
                    connection.set(gitUrl)
                    developerConnection.set(gitUrl)
                    url.set(siteUrl)
                }
            }
        }

        repositories {
            maven("https://oss.sonatype.org/service/local/staging/deploy/maven2/") {
                name = "sonatype"
                credentials {
                    username = ossUser
                    password = ossPassword
                }
            }
        }
    }

    nexusStaging {
        username = ossUser
        password = ossPassword
        packageGroup = publishedGroupId
    }
}

allprojects {
    afterEvaluate {
        // temp fix until sqllight includes https://github.com/cashapp/sqldelight/pull/3671
        project.extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            ?.let { kmpExt ->
                kmpExt.targets
                    .filterIsInstance<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
                    .flatMap { it.binaries }
                    .forEach { it.linkerOpts("-lsqlite3") }
            }
    }
}
