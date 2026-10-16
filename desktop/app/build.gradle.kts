plugins { kotlin("jvm"); application }
dependencies {
    implementation(project(":scripts"))
    implementation(libs.kotlinx.serialization.json)
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
application { mainClass.set("io.github.fga.desktop.MainKt") }
