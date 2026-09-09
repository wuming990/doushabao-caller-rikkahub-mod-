plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.workspace"

    // 探路（迁移形态模拟）：不在本环境编译 native，改用二创已有的预编译 .so。
    // 依据：2.4.5 与 2.4.8 的 workspace/src/main/cpp 内容完全相同（diff 无差异）。
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
