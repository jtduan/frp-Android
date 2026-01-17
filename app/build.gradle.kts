import com.android.build.api.dsl.ApkSigningConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val fileSigningAvailable = keystorePropertiesFile.exists() && listOf(
    "keyAlias", "keyPassword", "storeFile", "storePassword"
).all { !keystoreProperties.getProperty(it).isNullOrBlank() }

val envSigningAvailable = listOf("KEY_ALIAS", "KEY_PASSWORD", "STORE_FILE", "STORE_PASSWORD").all {
    !System.getenv(it).isNullOrBlank()
}

android {
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lateinit var releaseSigning: ApkSigningConfig

    signingConfigs {
        val aceSigning = when {
            fileSigningAvailable -> {
                create("AceKeystore") {
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                    storeFile = file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                }
            }

            envSigningAvailable -> {
                create("AceKeystore") {
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                    storeFile = if (System.getenv("STORE_FILE")?.isNotBlank() == true) {
                        // CI 跑脚本会生成 keystore.jks
                        file("../keystore.jks")
                    } else {
                        file(System.getenv("STORE_FILE"))
                    }
                    storePassword = System.getenv("STORE_PASSWORD")
                }
            }

            else -> null
        }

        // 没有提供签名信息时，回退到 Android 默认的 debug 签名，保证本地/CI 可编译
        releaseSigning = aceSigning ?: getByName("debug")
    }

    defaultConfig {
        applicationId = "io.github.acedroidx.frp"
        minSdk = 23
        targetSdk = 36
        compileSdk = 36
        versionCode = 20
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        signingConfig = releaseSigning

        buildConfigField("String", "FrpcFileName", "\"libfrpc.so\"")
        buildConfigField("String", "FrpsFileName", "\"libfrps.so\"")
        buildConfigField("String", "FrpcConfigFileName", "\"frpc.toml\"")
        buildConfigField("String", "FrpsConfigFileName", "\"frps.toml\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                // Includes the default ProGuard rules files that are packaged with
                // the Android Gradle plugin. To learn more, go to the section about
                // R8 configuration files.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // Includes a local, custom Proguard rules file
                "proguard-rules.pro"
            )
            signingConfig = releaseSigning
        }
        getByName("debug") {
            signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    namespace = "io.github.acedroidx.frp"

    lint {
        disable += "NewApi"
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abiFilter = output.filters.find { it.filterType == "ABI" }
            val abi = abiFilter?.identifier ?: "universal"
            val versionName = defaultConfig.versionName
            output.outputFileName = "frp_${abi}_${versionName}.apk"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // Android Studio Preview support
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // UI Tests
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Optional - Integration with activities
    implementation("androidx.activity:activity-compose")

    // Tasker Plugin Library
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}