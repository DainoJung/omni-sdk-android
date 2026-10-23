plugins {
    id("com.android.library")
    `maven-publish`
}

// JitPack passes the tag being built as VERSION.
group = "com.github.DainoJung"
version = System.getenv("VERSION") ?: "0.2.0"

android {
    namespace = "app.omnigrowth.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Plain JVM tests: Android calls that slip through return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // The newest that still run on minSdk 21; an app that uses newer ones gets those (Gradle picks the highest).
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.DainoJung"
                artifactId = "omni-sdk-android"
                version = project.version.toString()
                pom {
                    name.set("Omni SDK for Android")
                    description.set("Send product events, run experiments and report pushes to Omni.")
                    url.set("https://github.com/DainoJung/omni-sdk-android")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
