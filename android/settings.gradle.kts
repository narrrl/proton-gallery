pluginManagement {
    repositories {
        // Kotlin is published to Maven Central. Do not ask Google for its
        // plugin marker or implementation: a Google repository failure must
        // not prevent resolving an unrelated Kotlin plugin on fresh runners.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        // Resolve the Kotlin verifier bundled with the exact locked Rust crate.
        // Not --offline: a fresh checkout (or CI) has not downloaded the crate yet,
        // and cargo only goes to the network for crates missing locally.
        val metadata = providers.exec {
            workingDir(rootDir.parentFile)
            commandLine("cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android")
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
