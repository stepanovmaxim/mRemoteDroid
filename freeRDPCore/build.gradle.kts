plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.freerdp.freerdpcore"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        // JNI keep rules, applied when the app is minified with R8
        consumerProguardFiles("consumer-rules.pro")

        // We only ship prebuilt FreeRDP core libs for these ABIs, so only build
        // the JNI glue for them.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DWITH_CLIENT_CHANNELS=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // The prebuilt core libs live next to their headers under jniLibs/<abi>;
            // keep the first copy if anything is duplicated during merge.
            pickFirsts += listOf(
                "**/libfreerdp3.so",
                "**/libfreerdp-client3.so",
                "**/libwinpr3.so",
                "**/libssl.so",
                "**/libcrypto.so",
                "**/libcjson.so"
            )
        }
    }

    // This is upstream FreeRDP code with many deprecation warnings; don't fail on lint.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
}
