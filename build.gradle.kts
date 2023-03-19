val libraryVersionPrefix: String by project
group "com.ditchoom"
version "$libraryVersionPrefix.0-SNAPSHOT"

allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    kotlin("multiplatform") apply false
    kotlin("android") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
}