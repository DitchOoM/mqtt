plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.plugins.kotlin.multiplatform.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.android.library.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.maven.publish.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.ktlint.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.dokka.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
}

gradlePlugin {
    plugins {
        register("ditchoom-version") {
            id = "com.ditchoom.version"
            implementationClass = "com.ditchoom.gradle.DitchoomVersionPlugin"
        }
        register("ditchoom-module") {
            id = "com.ditchoom.module"
            implementationClass = "com.ditchoom.gradle.DitchoomModulePlugin"
        }
    }
}
