// Allows the protocol/ownership tests to run without an Android SDK.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins { kotlin("jvm") version "2.4.10"; kotlin("plugin.serialization") version "2.4.10" }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name="fga-automation-contract"
