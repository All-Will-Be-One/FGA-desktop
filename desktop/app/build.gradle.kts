import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm"); application }
dependencies {
    implementation(project(":scripts"))
    implementation(libs.dagger.hilt.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
application { mainClass.set("io.github.fga.desktop.MainKt") }

// Build metadata only; does not install or start a runtime.
tasks.register("prepareRuntime") {
    dependsOn(tasks.classes)
    doLast {
        layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(
            sourceSets.main.get().runtimeClasspath.asPath
        )
    }
}

tasks.test { useJUnitPlatform() }
tasks.compileTestKotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
tasks.compileTestJava { sourceCompatibility="17";targetCompatibility="17";options.release.set(17) }
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach {
    it.configure { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,17) } }
}
