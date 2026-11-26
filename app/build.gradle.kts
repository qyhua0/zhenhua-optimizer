import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "top.modelx.droidcare"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.modelx.droidcare"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.0"
    }

    signingConfigs {
        create("rel") {
            val p = Properties()
            // 项目根 signing.properties 优先; 不存在则尝试本机的备用位置(开源仓库不包含任何密钥)
            val f = listOf(File(rootDir, "signing.properties"), File(System.getProperty("user.home"), ".openclaw/secure/droidcare-signing.properties"))
                .firstOrNull { it.exists() } ?: File("nonexistent")
            if (f.exists()) f.inputStream().use { p.load(it) }
            if (p.getProperty("storeFile") != null) {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            runCatching {
                signingConfigs.getByName("rel").storeFile?.let { signingConfig = signingConfigs.getByName("rel") }
            }
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
        aidl = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
