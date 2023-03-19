import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint")
    id("io.codearte.nexus-staging")
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
    linuxX64()
    ios()
    iosX64()
    iosSimulatorArm64()
    tasks.getByName<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        deviceId = "iPhone 14"
    }
    watchos()
    watchosSimulatorArm64()
    tvos()
    tvosSimulatorArm64()

    sourceSets {
        val bufferVersion = extra["buffer.version"] as String
        val commonMain by getting {
            dependencies {
                implementation("com.ditchoom:buffer:$bufferVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting
        val androidTest by getting
        val androidAndroidTest by getting {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonTest/kotlin")
        }
        val jsMain by getting
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 9
        targetSdk = 33
    }
    namespace = "com.ditchoom.mqtt"
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

// System.getenv("GITHUB_REPOSITORY")?.let {
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

project.group = group
project.version = libraryVersion
publishing {
    publications.withType(MavenPublication::class) {
        groupId = project.group.toString()
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
    packageGroup = project.group.toString()
}
// }
