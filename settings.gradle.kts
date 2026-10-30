pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "NewAccessibleApp"
include(":app")
// Pure Kotlin speech engine (also built and tested on its own: gradle -p engine test)
includeBuild("engine")
