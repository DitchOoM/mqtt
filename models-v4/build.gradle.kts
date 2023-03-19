plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("io.codearte.nexus-staging")
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint")
    id("app.cash.sqldelight")
}

val libraryVersionPrefix: String by project
val publishedGroupId: String by project
group = publishedGroupId
version = "${libraryVersionPrefix}0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")) + 0)}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}

repositories {
    google()
    mavenCentral()
    mavenLocal()
}

kotlin {
    android {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
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
        nodejs()
    }
    macosX64()
    macosArm64()
    ios()
    iosSimulatorArm64()
    tasks.getByName<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        deviceId = "iPhone 14"
    }
    watchos()
    watchosSimulatorArm64()
    tvos()
    tvosSimulatorArm64()
    sourceSets {
        val sqldelightVersion = extra["sqldelight.version"] as String
        val bufferVersion = extra["buffer.version"] as String
        val coroutinesVersion = extra["coroutines.version"] as String
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation(project(":models-base"))
                implementation("com.ditchoom:buffer:$bufferVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            kotlin.srcDir("src/androidMain/kotlin")
            dependencies {
                implementation("app.cash.sqldelight:android-driver:$sqldelightVersion")
                compileOnly("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
            }
        }

        val androidTest by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
                implementation("org.xerial:sqlite-jdbc:3.6.22")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin-wrappers:kotlin-browser:1.0.0-pre.484")
            }
        }
        val macosX64Main by getting
        val macosX64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val iosMain by getting
        val iosTest by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting
        val watchosMain by getting
        val watchosTest by getting
        val watchosSimulatorArm64Main by getting
        val watchosSimulatorArm64Test by getting
        val tvosMain by getting
        val tvosTest by getting
        val tvosSimulatorArm64Main by getting
        val tvosSimulatorArm64Test by getting

        val appleMain by sourceSets.creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleMain/kotlin")
            macosX64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            iosMain.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            tvosMain.dependsOn(this)
            tvosSimulatorArm64Main.dependsOn(this)
            watchosMain.dependsOn(this)
            watchosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:$sqldelightVersion")
            }
        }

        val appleTest by sourceSets.creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/appleTest/kotlin")
            macosX64Test.dependsOn(this)
            macosArm64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            tvosTest.dependsOn(this)
            tvosSimulatorArm64Test.dependsOn(this)
            watchosTest.dependsOn(this)
            watchosSimulatorArm64Test.dependsOn(this)
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 16
        targetSdk = 33
    }
    namespace = "com.ditchoom.mqtt3"
    lint {
        abortOnError = false
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

if (System.getenv("GITHUB_REF") == "refs/heads/main") {
    signing {
        useInMemoryPgpKeys("56F1A973", System.getenv("GPG_SECRET"), System.getenv("GPG_SIGNING_PASSWORD"))
        sign(publishing.publications)
    }
}

val ossUser = System.getenv("SONATYPE_NEXUS_USERNAME")
val ossPassword = System.getenv("SONATYPE_NEXUS_PASSWORD")
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
val artifactName: String by project

project.group = publishedGroupId
project.version = libraryVersion

publishing {
    publications.withType(MavenPublication::class) {
        groupId = publishedGroupId
        version = libraryVersion
        artifactId = artifactName

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

ktlint {
    filter {
        exclude {
            it.file.absolutePath.contains("build/")
        }
    }
}

sqldelight {
    database("Mqtt4") {
        packageName = group.toString()
        sourceFolders = listOf("sqldelight")
    }
    linkSqlite = true
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
