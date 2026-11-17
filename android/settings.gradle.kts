pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Resolve the Kotlin verifier bundled with the exact locked Rust crate.
        val metadata = providers.exec {
            workingDir(rootDir.parentFile)
            commandLine("cargo", "metadata", "--locked", "--offline", "--format-version", "1", "--filter-platform", "aarch64-linux-android")
        }.standardOutput.asText.get()
        @Suppress("UNCHECKED_CAST")
        val packages = (groovy.json.JsonSlurper().parseText(metadata) as Map<String, Any>)["packages"] as List<Map<String, Any>>
        val verifier = packages.single { it["name"] == "rustls-platform-verifier-android" }
        maven {
            url = uri(File(verifier["manifest_path"] as String).parentFile.resolve("maven"))
            metadataSources { artifact() }
            content { includeGroup("rustls") }
        }
    }
}
rootProject.name = "ProtonGallery"
include(":app")
