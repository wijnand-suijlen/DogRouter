pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // BRouter publishes brouter-core to GitHub Packages only; needs a
        // classic PAT with read:packages. Credentials are read from
        // ~/.gradle/gradle.properties (gpr.user / gpr.key) or the
        // GITHUB_ACTOR / GITHUB_TOKEN env vars. See README.md.
        maven {
            name = "GitHubPackagesBRouter"
            url = uri("https://maven.pkg.github.com/abrensch/brouter")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "DogRouter"
include(":app")
