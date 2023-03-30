plugins {
    kotlin("multiplatform")
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
    js("serviceworker", IR) {
        browser {
            webpackTask {
                outputFileName = "serviceworker.js"
            }
            distribution {
                name = "serviceworker"
            }
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
        val serviceworkerMain by getting {

            val websocketVersion = extra["websocket.version"] as String
            val bufferVersion = extra["buffer.version"] as String
            dependencies {
                api(project(":models-base"))
                api(project(":models-v4"))
                api(project(":models-v5"))
                api(project(":mqtt-client"))
                api("com.ditchoom:websocket:$websocketVersion")
                api("com.ditchoom:buffer:$bufferVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.6.4")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.8.0")
            }
        }

        val jsMain by getting {
            resources.srcDirs("./build/serviceworker")
            dependsOn(serviceworkerMain)
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }


    }
}
tasks["jsProcessResources"].dependsOn.add("serviceworkerBrowserDistribution")
