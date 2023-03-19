plugins {
    kotlin("multiplatform")
}

group "com.ditchoom"
version "1.0-SNAPSHOT"
repositories { maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") } }

kotlin {
    js(IR) {
        browser {
            testTask {
                testLogging.showStandardStreams = true
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }
    sourceSets {
        val jsMain by getting {
            dependencies {
                api(project(":models-base"))
                api(project(":models-v4"))
                api(project(":models-v5"))
                api(project(":mqtt-client"))
                api("com.ditchoom:buffer:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.8.0")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}