plugins {
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "com.miokzz.rawbench"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.miokzz.rawbench"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "lab"
        ndk.abiFilters += "arm64-v8a"
    }
    packaging.jniLibs.useLegacyPackaging = true
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    providers.gradleProperty("rawResidentSdk").orNull?.let { sdk ->
        defaultConfig.externalNativeBuild.cmake.arguments += "-DRAW_RESIDENT_SDK=$sdk"
        sourceSets.getByName("main").jniLibs.srcDir("$sdk/runtime")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    val localQnn = providers.gradleProperty("rawQnnDir").orNull
    if (localQnn == null) {
        implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
        implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")
    } else {
        implementation(files("$localQnn/qnn-runtime.aar", "$localQnn/qnn-litert-delegate.aar"))
    }
    testImplementation("junit:junit:4.13.2")
}
