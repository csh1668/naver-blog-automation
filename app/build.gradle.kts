import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

fun propOrNull(name: String): String? = keystoreProps?.getProperty(name)

android {
    namespace = "com.csh.blogwriter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.csh.blogwriter"
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_REPO", "\"csh1668/naver-blog-automation\"")
    }

    val releaseKeystorePath = System.getenv("KEYSTORE_PATH") ?: propOrNull("storeFile")

    signingConfigs {
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: propOrNull("storePassword")
                keyAlias = System.getenv("KEY_ALIAS") ?: propOrNull("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD") ?: propOrNull("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 키스토어가 없으면(예: CI 의 빌드 확인) 서명하지 않고 unsigned APK 로 만든다.
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release") else null
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
