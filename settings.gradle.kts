pluginManagement {
    includeBuild("build-logic")
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "sprout"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":benchmark")
include(":widget")

include(":core:ai")
include(":core:common")
include(":core:crypto")
include(":core:database")
include(":core:designsystem")
include(":core:domain-household")
include(":core:domain-ledger")
include(":core:model")
include(":core:sync")
include(":core:sync-firebase")
include(":core:testing")
include(":core:time")
include(":core:ui")

include(":feature:chat")
include(":feature:chores")
include(":feature:games")
include(":feature:lock")
include(":feature:onboarding")
include(":feature:parent-home")
include(":feature:settings")
include(":feature:timebank")
include(":feature:wallet")
