rootProject.name = "openosrs"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("allowMavenLocal").orNull == "true") {
            check(providers.environmentVariable("CI").orNull == null) { "mavenLocal is a development-only override" }
            mavenLocal { content { includeGroup("com.openosrs"); includeGroup("net.openosrs") } }
        }
        exclusiveContent {
            forRepository { maven { name = "RuneLite"; url = uri("https://repo.runelite.net") } }
            filter {
                includeModule("net.runelite", "discord")
                includeModule("net.runelite", "orange-extensions")
                includeModule("net.runelite", "rlawt")
                includeModule("net.runelite.pushingpixels", "substance")
                includeModule("net.runelite.pushingpixels", "trident")
            }
        }
        mavenCentral {
            content { excludeGroupByRegex("net\\.runelite(\\..*)?") }
        }
    }
}

include(":http-api")
include(":runelite-api")
include(":openosrs-api")
include(":runelite-client")
include(":runelite-jshell")
