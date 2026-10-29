plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.android.tools.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Fixtures live at the repo root so every module can share them.
    systemProperty("pulseforge.fixtures", rootProject.file("fixtures").absolutePath)
    maxHeapSize = "1g"
}
