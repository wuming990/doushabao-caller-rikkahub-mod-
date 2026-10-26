plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.workspace"

    // v323：本环境没有可用的 Android CMake；workspace 的 .so 已随 jniLibs 提供，
    // 这里不再触发 C++ 重编（与历史发布包的产物一致）。
    // 如需重新编译 native：装好 CMake 3.22.1 后把下面两段恢复即可。
    /*
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    */
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    // v1017: official SSH workspace dependencies.
    implementation(libs.jsch)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    // v1017: official embedded SSH/SFTP server used only by workspace unit tests.
    testImplementation(libs.apache.sshd.core)
    testImplementation(libs.apache.sshd.sftp)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
