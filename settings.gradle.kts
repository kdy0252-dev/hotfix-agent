dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "my-agent"

includeBuild("build-logic")
include(":app")
