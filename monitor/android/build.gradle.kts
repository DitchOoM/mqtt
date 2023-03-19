plugins {
    id("org.jetbrains.compose")
    id("com.android.application")
    kotlin("android")
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
    jcenter()
}

dependencies {
    api(project(":monitor:common"))
    implementation("androidx.activity:activity-compose:1.6.1")
}

android {
    compileSdkVersion(33)
    defaultConfig {
        applicationId = "com.ditchoom.android"
        minSdkVersion(24)
        targetSdkVersion(33)
        versionCode = 1
        versionName = "1.0-SNAPSHOT"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    lint {
        abortOnError = false
    }
}