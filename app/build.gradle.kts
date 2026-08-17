// App 只依赖 Probe 公共制品，不直接声明 Media3/ExoPlayer。
plugins {
    id("com.android.application")
}

// CI 从发布 tag 注入版本；本地构建保留稳定的开发版本。
val ciVersionCode = providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.gradleProperty("ciVersionName").orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "0.1.0"

android {
    namespace = "com.fongmi.ad.collector"
    compileSdk = 35

    flavorDimensions += "abi"
    productFlavors {
        // 三个发布变体分别保留对应 ABI 的原生依赖，便于按设备下载。
        create("arm64_v8a") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("armeabi_v7a") {
            dimension = "abi"
            ndk {
                abiFilters += "armeabi-v7a"
            }
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    defaultConfig {
        applicationId = "com.fongmi.ad.collector"
        minSdk = 23
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 公开测试签名固定随仓库发布，保证自动构建的 APK 可以相互覆盖安装。
        create("release") {
            storeFile = rootProject.file("signing/collector-test.jks")
            storePassword = "m3u8-ad-audio-collector"
            keyAlias = "collector-test"
            keyPassword = "m3u8-ad-audio-collector"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    // AndroidX 的协程传递依赖仍请求旧拆分 stdlib，用 BOM 避免 1.6/1.8 重复类。
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("io.github.0o755:ad-audio-probe:0.1.0-SNAPSHOT")

    testImplementation("junit:junit:4.13.2")
}
