plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ricardopimentel.cachalote"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ricardopimentel.cachalote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0-f2"
    }

    // Assinatura de release: a chave vive FORA do repositório (por omissão em .work/).
    // Gera-a com tools/build-release.sh; para distribuir, guarda-a e usa variáveis de
    // ambiente (CACHALOTE_KEYSTORE / CACHALOTE_STORE_PASS / CACHALOTE_KEY_ALIAS).
    signingConfigs {
        create("release") {
            val ks = file(System.getenv("CACHALOTE_KEYSTORE") ?: "${rootDir}/../.work/cachalote-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("CACHALOTE_STORE_PASS") ?: "cachalote"
                keyAlias = System.getenv("CACHALOTE_KEY_ALIAS") ?: "cachalote"
                keyPassword = System.getenv("CACHALOTE_KEY_PASS") ?: "cachalote"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Runtime Node embutido como biblioteca nativa (flavor "in-app", sem root):
    // gerado por tools/prepare-embed.sh. useLegacyPackaging obriga a APK a EXTRAIR os
    // .so para o nativeLibraryDir — é lá que o exec é permitido com targetSdk moderno.
    sourceSets["main"].jniLibs.srcDir("${rootDir}/../.work/jniLibs")
    // O kernel vai como zip já comprimido: recomprimi-lo no APK só gastaria minutos de build.
    androidResources { noCompress += listOf("zip") }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Shizuku: modo "shell" sem root (ver ShizukuBridge).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
