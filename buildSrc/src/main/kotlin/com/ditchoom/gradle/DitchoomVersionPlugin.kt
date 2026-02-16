package com.ditchoom.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class DitchoomVersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val artifact = project.property("versionLookupArtifact") as String

        val getNextVersion: (Boolean) -> Version = { snapshot ->
            val incrementMajor =
                project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true"
            val incrementMinor =
                project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true"
            VersionCalculator.getNextVersion(artifact, snapshot, incrementMajor, incrementMinor)
        }

        project.extensions.extraProperties.set("getNextVersion", getNextVersion)
    }
}
