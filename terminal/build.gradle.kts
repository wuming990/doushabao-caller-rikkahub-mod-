plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    // v1017：本环境 NDK 只有 x86_64 工具链且没有执行位，arm64 主机跑不了原生编译
    // （与 ai / workspace 两个模块既有「注释掉原生构建」的处境相同）。
    // 用户 2026-10-26 拍板方案 C：采用官方 3.1.1 发布包里编好的 libtermux.so，
    // 预置在 app/src/main/jniLibs/arm64-v8a/，因此这里不再声明原生构建。
    // 来源与哈希记录：/workspace/.tooling/v1017/applied-libtermux-311-provenance.json
    // （官方包 SHA-256 与 GitHub 公布摘要一致；libtermux.so SHA-256 8cf4a53a…33b4732）
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    testOptions {
        // 上游单测会碰到 android.util.Base64 等 Android 类, 沿用上游的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
