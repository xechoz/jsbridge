import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.wire)
}

android {
    namespace = "me.xechoz.jsbridge.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.xechoz.jsbridge.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

wire {
    sourcePath {
        srcDir("$rootDir/proto")
    }
    kotlin {
        rpcRole = "none"
    }
}

dependencies {
    implementation(project(":jsbridge"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wire.runtime)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
