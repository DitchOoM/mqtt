plugins {
    alias(libs.plugins.sqldelight) apply false
}

// Aggregate tasks for convenience
tasks.register("allTests") {
    description = "Run tests for all modules and platforms"
    group = "verification"
    dependsOn(":models-base:allTests", ":models-v4:allTests", ":models-v5:allTests", ":mqtt-client:allTests")
}

tasks.register("buildAll") {
    description = "Build all modules"
    group = "build"
    dependsOn(":models-base:build", ":models-v4:build", ":models-v5:build", ":mqtt-client:build")
}
