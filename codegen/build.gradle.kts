import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("me.xechoz.jsbridge.codegen.MainKt")
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.json)
    testImplementation(libs.junit)
}
