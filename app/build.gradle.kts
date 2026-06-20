plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.dogrouter"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dogrouter"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.osmdroid.android)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.brouter.core)
    implementation(libs.brouter.mapaccess)
    implementation(libs.brouter.codec)
    implementation(libs.brouter.expressions)
    implementation(libs.brouter.util)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Off-device solver harness (see SolverHarness / docs/STATUS.md). Run with
// -PsolverOutput to stream the solver report to the terminal and force the
// harness to re-run every time; any -Dsolver.* flags are forwarded to the
// test JVM. Normal test runs are unaffected.
tasks.withType<Test>().configureEach {
    System.getProperties().forEach { (k, v) ->
        if (k.toString().startsWith("solver.")) systemProperty(k.toString(), v.toString())
    }
    if (project.hasProperty("solverOutput")) {
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false }
    }
}
