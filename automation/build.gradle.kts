import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm"); alias(libs.plugins.kotlin.serialization) }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
}
kotlin { jvmToolchain(21) }
tasks.compileKotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
tasks.test { useJUnitPlatform() }
tasks.compileTestKotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
tasks.compileTestJava { sourceCompatibility="17"; targetCompatibility="17"; options.release.set(17) }
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach {
    it.configure { attributes { attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,17) } }
}
