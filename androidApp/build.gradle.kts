import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.sharedUI)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "dev.balaji.moonrose.trainfree"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    val enableTestDataOverride = providers.gradleProperty("enableTestData").orNull?.toBooleanStrictOrNull()

    defaultConfig {
        applicationId = "dev.balaji.moonrose.trainfree"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_TEST_DATA", "${enableTestDataOverride ?: true}")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_TEST_DATA", "${enableTestDataOverride ?: false}")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}