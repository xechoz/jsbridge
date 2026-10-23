import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
}

group = "io.github.xechoz"
version = "0.1.0"

android {
    namespace = "me.xechoz.jsbridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("io.github.xechoz", "jsbridge", version.toString())

    pom {
        name.set("jsbridge")
        description.set(
            "Bidirectional async RPC between Android WebView and web, with protobuf-typed APIs.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/xechoz/jsbridge")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("xechoz")
                name.set("xechoz")
                email.set("xechoz@users.noreply.github.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/xechoz/jsbridge.git")
            developerConnection.set("scm:git:ssh://git@github.com/xechoz/jsbridge.git")
            url.set("https://github.com/xechoz/jsbridge")
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
