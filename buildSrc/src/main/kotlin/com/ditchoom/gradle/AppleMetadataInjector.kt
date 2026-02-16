package com.ditchoom.gradle

import java.io.File

object AppleMetadataInjector {
    private val appleTargets =
        listOf(
            "iosArm64" to "ios_arm64",
            "iosSimulatorArm64" to "ios_simulator_arm64",
            "iosX64" to "ios_x64",
            "macosArm64" to "macos_arm64",
            "macosX64" to "macos_x64",
            "tvosArm64" to "tvos_arm64",
            "tvosSimulatorArm64" to "tvos_simulator_arm64",
            "tvosX64" to "tvos_x64",
            "watchosArm64" to "watchos_arm64",
            "watchosSimulatorArm64" to "watchos_simulator_arm64",
            "watchosX64" to "watchos_x64",
        )

    @Suppress("UNCHECKED_CAST")
    fun inject(
        moduleFile: File,
        version: String,
        artifactId: String,
    ) {
        val json = groovy.json.JsonSlurper().parseText(moduleFile.readText()) as MutableMap<String, Any>
        val variants = json["variants"] as MutableList<Any>

        appleTargets.forEach { (gradleName, konanName) ->
            val moduleName = "$artifactId-${gradleName.lowercase()}"
            val availableAt =
                mapOf(
                    "url" to "../../$moduleName/$version/$moduleName-$version.module",
                    "group" to "com.ditchoom",
                    "module" to moduleName,
                    "version" to version,
                )
            variants.add(
                mapOf(
                    "name" to "${gradleName}ApiElements-published",
                    "attributes" to
                        mapOf(
                            "org.gradle.category" to "library",
                            "org.gradle.jvm.environment" to "non-jvm",
                            "org.gradle.usage" to "kotlin-api",
                            "org.jetbrains.kotlin.native.target" to konanName,
                            "org.jetbrains.kotlin.platform.type" to "native",
                        ),
                    "available-at" to availableAt,
                ),
            )
            variants.add(
                mapOf(
                    "name" to "${gradleName}SourcesElements-published",
                    "attributes" to
                        mapOf(
                            "org.gradle.category" to "documentation",
                            "org.gradle.dependency.bundling" to "external",
                            "org.gradle.docstype" to "sources",
                            "org.gradle.jvm.environment" to "non-jvm",
                            "org.gradle.usage" to "kotlin-runtime",
                            "org.jetbrains.kotlin.native.target" to konanName,
                            "org.jetbrains.kotlin.platform.type" to "native",
                        ),
                    "available-at" to availableAt,
                ),
            )
        }

        moduleFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
    }
}
