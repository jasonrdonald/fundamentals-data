import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.fundamentalanalysis.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fundamentalanalysis.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.8.2"

        // Manifest of the shared dataset (schema 5); parts are fetched relative to it.
        val manifestUrl = providers.gradleProperty("dataset.manifestUrl").getOrElse("")
        buildConfigField("String", "MANIFEST_URL", "\"$manifestUrl\"")
        // Public page where the dataset pipeline can be run by hand (shown on the Data page).
        val pipelineUrl = providers.gradleProperty("dataset.pipelineUrl").getOrElse("")
        buildConfigField("String", "PIPELINE_URL", "\"$pipelineUrl\"")
        // Wishlist requests: the "Add to wishlist" workflow that approves them (public), and the
        // address request emails go to. The address is personal, so it is read from local.properties
        // (gitignored) or ~/.gradle/gradle.properties; without it the app opens the share sheet instead.
        val wishlistWorkflowUrl = providers.gradleProperty("wishlist.workflowUrl").getOrElse("")
        buildConfigField("String", "WISHLIST_WORKFLOW_URL", "\"$wishlistWorkflowUrl\"")
        val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        val adminEmail = providers.gradleProperty("wishlist.adminEmail").orNull ?: localProps.getProperty("wishlist.adminEmail", "")
        buildConfigField("String", "WISHLIST_ADMIN_EMAIL", "\"$adminEmail\"")
    }

    // Release signing key details come from keystore.properties (gitignored) when present, so the
    // repository builds without any secrets; without it, release builds are simply unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

// PublishedDatasetTest reads the published dataset: rerun the tests when it changes, not only the code.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("shared-data/v5")).withPropertyName("publishedDataset").optional()
}
