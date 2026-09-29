plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing values must never be serialized into Gradle's configuration cache.
check(!gradle.startParameter.isConfigurationCacheRequested) {
    "Configuration cache is disabled for secure signing. Use --no-configuration-cache."
}

val releaseSigningVariables = listOf(
    "PAKYAR_KEYSTORE_PATH", "PAKYAR_STORE_PASSWORD", "PAKYAR_KEY_ALIAS", "PAKYAR_KEY_PASSWORD"
)
val validateReleaseSigningEnvironment = tasks.register("validateReleaseSigningEnvironment") {
    group = "verification"
    description = "Checks environment-only release signing without displaying values."
    doLast {
        releaseSigningVariables.forEach { name ->
            if (System.getenv(name).isNullOrEmpty()) {
                throw GradleException("Missing environment variable: $name")
            }
        }
        if (file(System.getenv("PAKYAR_KEYSTORE_PATH")).name.equals("debug.keystore", ignoreCase = true)) {
            throw GradleException("The Android debug keystore is not permitted for Release.")
        }
    }
}

android {
    namespace = "ir.mehran.pakyar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ir.mehran.pakyar"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("PAKYAR_KEYSTORE_PATH").orNull
                ?.takeIf { it.isNotEmpty() }?.let { file(it) }
            storePassword = providers.environmentVariable("PAKYAR_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("PAKYAR_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("PAKYAR_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Works for assemble/bundle/package and aggregate build tasks, not just CLI task-name guesses.
// No Release task is a dependency of a normal Debug build.
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        dependsOn(validateReleaseSigningEnvironment)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
