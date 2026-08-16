// App 只依赖 Probe 公共制品，不直接声明 Media3/ExoPlayer。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.fongmi.ad.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fongmi.ad.collector"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
