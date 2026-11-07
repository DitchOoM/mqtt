package com.ditchoom.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.io.File

/** The `ditchoom*Version` properties that point a sibling library at a locally published build. */
internal enum class SiblingVersionProperty(
    val gradleName: String,
) {
    Buffer("ditchoomBufferVersion"),
    Socket("ditchoomSocketVersion"),
    Websocket("ditchoomWebsocketVersion"),
}

/** Why a build searches mavenLocal for the DitchOoM sibling libraries. */
internal sealed interface MavenLocalReason {
    /** Off CI: a developer's own snapshots. */
    data object DeveloperMachine : MavenLocalReason

    /** `.github/actions/upstream-from-source` published an upstream library there. */
    data object BuiltFromSource : MavenLocalReason

    /** A sibling version property names a locally published snapshot (the downstream canary). */
    data class Overridden(
        val property: SiblingVersionProperty,
    ) : MavenLocalReason
}

/**
 * Where this build resolves the DitchOoM sibling libraries from. mavenLocal holds whatever the machine last
 * published, so CI searches it only when it was filled on purpose.
 */
internal sealed interface SiblingSource {
    fun addRepositories(repositories: RepositoryHandler)

    data class SearchesMavenLocal(
        val reason: MavenLocalReason,
    ) : SiblingSource {
        override fun addRepositories(repositories: RepositoryHandler) {
            repositories.mavenLocal()
        }
    }

    /** CI with nothing published locally on purpose, which includes every release. */
    data object CentralOnly : SiblingSource {
        override fun addRepositories(repositories: RepositoryHandler) = Unit
    }

    companion object {
        /** The variable `.github/actions/upstream-from-source` sets after building an upstream library. */
        private const val BUILT_FROM_SOURCE_ENV = "DITCHOOM_UPSTREAM_FROM_SOURCE"

        fun of(
            project: Project,
            isRunningOnGithub: Boolean,
        ): SiblingSource {
            val overridden = SiblingVersionProperty.entries.filter { project.providers.gradleProperty(it.gradleName).isPresent }
            return when {
                !isRunningOnGithub -> SearchesMavenLocal(MavenLocalReason.DeveloperMachine)
                System.getenv(BUILT_FROM_SOURCE_ENV) == "true" -> SearchesMavenLocal(MavenLocalReason.BuiltFromSource)
                overridden.isNotEmpty() -> SearchesMavenLocal(MavenLocalReason.Overridden(overridden.first()))
                else -> CentralOnly
            }
        }
    }
}

/** A module resolved dependency files from mavenLocal, which a release must not ship against. */
internal class ResolvedFromMavenLocal(
    val modulePath: String,
    val mavenLocal: File,
    val files: Set<File>,
) : GradleException(
        "$modulePath resolved ${files.size} file(s) from mavenLocal ($mavenLocal):\n" +
            files.sorted().joinToString("\n") { "  $it" },
    )

/**
 * `verifyCentralOnly` fails when any resolved dependency of this module is a file in mavenLocal. The release
 * build runs it, so a release cannot ship against an artifact that only this runner has.
 */
internal fun registerCentralOnlyGuard(project: Project) {
    val mavenLocal = File(System.getProperty("maven.repo.local") ?: "${System.getProperty("user.home")}/.m2/repository")
    val resolved = project.objects.fileCollection()
    project.configurations
        .matching { it.isCanBeResolved && CLASSPATH_SUFFIXES.any(it.name::endsWith) }
        .configureEach { resolved.from(incoming.artifactView { lenient(true) }.files) }
    val modulePath = project.path
    project.tasks.register("verifyCentralOnly") {
        group = "verification"
        description = "Fails if any resolved dependency came from mavenLocal (releases resolve from Maven Central only)."
        inputs.files(resolved)
        doLast {
            val local = resolved.files.filter { it.startsWith(mavenLocal) }.toSet()
            if (local.isNotEmpty()) throw ResolvedFromMavenLocal(modulePath, mavenLocal, local)
        }
    }
}

private val CLASSPATH_SUFFIXES = listOf("CompileClasspath", "RuntimeClasspath", "CompileKlibraries")
