pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "fga-desktop"
include(":libautomata", ":scripts", ":desktop-cli")
project(":libautomata").projectDir = file("../libautomata")
project(":scripts").projectDir = file("../scripts")
project(":desktop-cli").projectDir = file("app")
