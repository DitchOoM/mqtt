package com.ditchoom.gradle

import groovy.util.Node
import groovy.xml.XmlParser
import java.net.URL

data class Version(
    val major: UInt,
    val minor: UInt,
    val patch: UInt,
    val snapshot: Boolean,
) {
    constructor(string: String, snapshot: Boolean) :
        this(
            string.split('.')[0].toUInt(),
            string.split('.')[1].toUInt(),
            string.split('.')[2].toUInt(),
            snapshot,
        )

    fun incrementMajor() = Version(major + 1u, 0u, 0u, snapshot)

    fun incrementMinor() = Version(major, minor + 1u, 0u, snapshot)

    fun incrementPatch() = Version(major, minor, patch + 1u, snapshot)

    fun snapshot() = Version(major, minor, patch, true)

    fun isVersionZero() = major == 0u && minor == 0u && patch == 0u

    override fun toString(): String =
        if (snapshot) {
            "$major.$minor.$patch-SNAPSHOT"
        } else {
            "$major.$minor.$patch"
        }
}

object VersionCalculator {
    private var cachedVersion: Version? = null

    @Suppress("UNCHECKED_CAST")
    fun getLatestVersion(artifact: String): Version {
        cachedVersion?.let { if (!it.isVersionZero()) return it }
        val xml =
            URL("https://repo1.maven.org/maven2/com/ditchoom/$artifact/maven-metadata.xml").readText()
        val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
        val latestStringList = versioning.first()["latest"] as List<Node>
        val result = Version((latestStringList.first().value() as List<*>).first().toString(), false)
        cachedVersion = result
        return result
    }

    fun getNextVersion(
        artifact: String,
        snapshot: Boolean,
        incrementMajor: Boolean,
        incrementMinor: Boolean,
    ): Version {
        var v = getLatestVersion(artifact)
        if (snapshot) {
            v = v.snapshot()
        }
        return when {
            incrementMajor -> v.incrementMajor()
            incrementMinor -> v.incrementMinor()
            else -> v.incrementPatch()
        }
    }
}
