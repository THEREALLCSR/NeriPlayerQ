plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.feature.player"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.withType<Test>().configureEach {
    // Android 16 sandbox classes require Java 21; production bytecode still targets Java 17.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    // Android 16 tests access the JDK file descriptor bridge during shared memory setup.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")

    // Robolectric 默认自己去 Maven 拉 android-all-instrumented（每个 100~200MB）。
    // 网络受限的机器上这一步失败 = 整个 Robolectric 测试类连跑都跑不起来，
    // 于是"红了"不再等于"有问题"。设了 NERIL_ROBOLECTRIC_DEPS 就用本地目录，
    // 没设则保持原行为（CI 上照常联网拉取）。
    System.getenv("NERIL_ROBOLECTRIC_DEPS")?.takeIf { it.isNotBlank() }?.let { localDeps ->
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", localDeps)
    }

    // runTest 默认 60s 超时：一个挂死的用例要占满一分钟，整类排查要等半小时。
    // 设 NERIL_TEST_TIMEOUT 可缩短（如 5s），默认不设则保持 60s。
    System.getenv("NERIL_TEST_TIMEOUT")?.takeIf { it.isNotBlank() }?.let { timeout ->
        systemProperty("kotlinx.coroutines.test.default_timeout", timeout)
    }
}

dependencies {
    testImplementation(testFixtures(project(":common")))
    implementation(project(":common"))
    implementation(project(":native"))
    implementation(project(":lyrics"))
    implementation(project(":network"))
    implementation(project(":playback:logic"))
    implementation(project(":platform"))
    implementation(project(":database"))
    implementation(project(":listentogether"))
    implementation(project(":model"))
    implementation(project(":local"))
    implementation(project(":sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.icons)
    implementation(libs.androidx.appcompat)

    // HyperOS Focus notifications and privileged UID network control.
    implementation(libs.focus.api)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hidden.api.bypass)
    compileOnly(project(":hidden-api"))

    // 解码器的 native 库由应用打包, 库模块只使用编译接口
    compileOnly(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
    testImplementation(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
}
