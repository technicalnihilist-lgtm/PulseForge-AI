pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PulseForgeAI"

include(":app")
include(":core:model")
include(":core:apk")
include(":core:security")
include(":core:performance")
include(":core:report")
include(":core:data")
include(":core:ai")
include(":core:ui")
