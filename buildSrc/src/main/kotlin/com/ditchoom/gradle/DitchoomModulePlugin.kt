package com.ditchoom.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.konan.target.HostManager

class DitchoomModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
        val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

        @Suppress("UNCHECKED_CAST")
        val getNextVersion = project.extensions.extraProperties["getNextVersion"] as (Boolean) -> Any
        project.version = getNextVersion(!isRunningOnGithub).toString()

        project.logger.lifecycle(
            "Version: ${project.version}, isRunningOnGithub: $isRunningOnGithub, isMainBranchGithub: $isMainBranchGithub",
        )

        project.repositories.google()
        project.repositories.mavenCentral()
        project.repositories.mavenLocal()

        project.tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")
        }

        val publishedGroupId = project.property("publishedGroupId") as String
        val libraryName = project.property("libraryName") as String
        val artifactName = project.property("artifactName") as String
        val libraryDescription = project.property("libraryDescription") as String
        val siteUrl = project.property("siteUrl") as String
        val gitUrl = project.property("gitUrl") as String
        val licenseName = project.property("licenseName") as String
        val licenseUrl = project.property("licenseUrl") as String
        val developerOrg = project.property("developerOrg") as String
        val developerName = project.property("developerName") as String
        val developerEmail = project.property("developerEmail") as String
        val developerId = project.property("developerId") as String

        project.group = publishedGroupId

        val signingInMemoryKey = project.findProperty("signingInMemoryKey")
        val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
        val shouldSignAndPublish =
            isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

        if (shouldSignAndPublish) {
            project.extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(
                    signingInMemoryKey as String,
                    signingInMemoryKeyPassword as String,
                )
                sign(project.extensions.getByType(PublishingExtension::class.java).publications)
            }
        }

        project.extensions.configure<MavenPublishBaseExtension> {
            if (shouldSignAndPublish) {
                publishToMavenCentral()
                signAllPublications()
            }

            coordinates(publishedGroupId, artifactName, project.version.toString())

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

        project.extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            verbose.set(true)
            outputToConsole.set(true)
        }

        project.tasks.register("nextVersion") {
            doLast {
                println(getNextVersion(false))
            }
        }

        project.afterEvaluate {
            if (isRunningOnGithub) {
                if (HostManager.hostIsLinux) {
                    tasks.named("generateMetadataFileForKotlinMultiplatformPublication") {
                        doLast {
                            val moduleFile = outputs.files.singleFile
                            AppleMetadataInjector.inject(moduleFile, project.version.toString(), artifactName)
                        }
                    }
                }
                if (HostManager.hostIsMac) {
                    tasks
                        .matching {
                            it.name.startsWith("publishKotlinMultiplatformPublication")
                        }.configureEach { enabled = false }
                }
            }
        }
    }
}
