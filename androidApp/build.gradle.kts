import java.io.File
import java.io.StringReader
import java.util.Properties
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
    val keystoreProperties = Properties().apply {
        providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
            .asText
            .orNull
            ?.let { load(StringReader(it)) }
    }
    fun signingValue(propKey: String, envKey: String): String? =
        keystoreProperties.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: providers.environmentVariable(envKey).orNull?.trim()?.takeIf { it.isNotEmpty() }

    val releaseStoreFile = signingValue("storeFile", "TRAINFREE_UPLOAD_STORE_FILE")
    val releaseStorePassword = signingValue("storePassword", "TRAINFREE_UPLOAD_STORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "TRAINFREE_UPLOAD_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "TRAINFREE_UPLOAD_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { it != null }

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
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val resolvedStoreFile = File(releaseStoreFile!!).let { candidate ->
                    if (candidate.isAbsolute) candidate
                    else rootProject.layout.projectDirectory.file(releaseStoreFile).asFile
                }
                storeFile = resolvedStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_TEST_DATA", "${enableTestDataOverride ?: true}")
        }
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            buildConfigField("boolean", "ENABLE_TEST_DATA", "${enableTestDataOverride ?: false}")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}