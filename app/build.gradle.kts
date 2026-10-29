import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from keystore.properties, which is git-ignored. When it is absent the
// release build falls back to the debug key so CI can still produce a build; the About screen
// shows the signing identity so a debug-signed "release" is never mistaken for a shipped one.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.pulseforge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pulseforge.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "META-INF/LICENSE*")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:apk"))
    implementation(project(":core:security"))
    implementation(project(":core:performance"))
    implementation(project(":core:report"))
    implementation(project(":core:data"))
    implementation(project(":core:ai"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // Provides the debug manifest entry that createComposeRule() needs; contains no classes.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Robolectric runs the Compose screens on the JVM. The android-all jar is fetched by
// tools/fetch-robolectric.sh so tests work without network access.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")

    // Offline mode only when the pre-fetched jars are present and requested, because Robolectric
    // also needs the nativeruntime artifact for @GraphicsMode(NATIVE) screenshot capture.
    if (System.getenv("PF_OFFLINE") == "true") {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", rootProject.file("tools/robolectric-deps").absolutePath)
    }
    System.getenv("PF_TRUSTSTORE")?.let {
        systemProperty("javax.net.ssl.trustStore", it)
        systemProperty("javax.net.ssl.trustStorePassword", System.getenv("PF_TRUSTSTORE_PASSWORD") ?: "changeit")
    }
    systemProperty("pulseforge.fixtures", rootProject.file("fixtures").absolutePath)
    systemProperty("pulseforge.screenshots", rootProject.file("screenshots").absolutePath)
    listOf(
        "http.proxyHost" to "PF_PROXY_HOST",
        "http.proxyPort" to "PF_PROXY_PORT",
        "https.proxyHost" to "PF_PROXY_HOST",
        "https.proxyPort" to "PF_PROXY_PORT"
    ).forEach { (property, env) -> System.getenv(env)?.let { systemProperty(property, it) } }
}
