pluginManagement {
    repositories {
        providers.gradleProperty("kronosRepository").orNull?.let { location ->
            exclusiveContent {
                forRepository { maven { url = uri(location) } }
                filter { includeGroup("com.kotlinorm"); includeGroup("com.kotlinorm.kronos-gradle-plugin") }
            }
        }
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("kronosRepository").orNull?.let { location ->
            exclusiveContent {
                forRepository { maven { url = uri(location) } }
                filter { includeGroup("com.kotlinorm"); includeGroup("com.kotlinorm.kronos-gradle-plugin") }
            }
        }
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "kronos-example-ios"
include(":shared")
