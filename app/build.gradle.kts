plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.rafaelcs28.vidrostravas"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.rafaelcs28.vidrostravas"
        minSdk = 28
        // A central e Android 9; targetSdk 28 evita as restricoes de background das versoes
        // seguintes, que nao ajudam em nada aqui e so atrapalhariam a captura.
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 6
        versionName = "1.5"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug de proposito: e um utilitario descartavel, instalado
            // por link e desinstalado quando o diagnostico terminar.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pelo FileProvider: a partir do targetSdk 24 o Android proibe entregar file:// num Intent,
    // e era isso que derrubava o aplicativo no botao de compartilhar.
    implementation("androidx.core:core:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
