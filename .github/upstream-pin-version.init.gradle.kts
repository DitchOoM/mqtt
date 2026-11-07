// Pins every project of an upstream DitchOoM build to -PupstreamVersion, so a library built from
// source publishes at exactly the version mqtt's catalog asks for. websocket computes its version
// from Maven Central in its build script, ignores -Pversion, and hands that string to its
// publications while the script runs, so both the project and each publication are pinned after it.
// Nested builds (buildSrc) publish nothing and do not receive -P, so only the top build is pinned.
if (gradle.parent == null) {
    val pinned = startParameter.projectProperties.getValue("upstreamVersion")

    allprojects {
        afterEvaluate {
            version = pinned
            plugins.withId("maven-publish") {
                extensions.getByType(PublishingExtension::class.java).publications.withType(MavenPublication::class.java).configureEach {
                    version = pinned
                }
            }
        }
    }
}
