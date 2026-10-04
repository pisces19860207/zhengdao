plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.zhengdao"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.zhengdao"
        // 架构生死线（设计文档 v3「架构前提」）：
        // targetSdk 必须钉死 28 —— Android 10 起，targetSdk ≥ 29 的应用会被 SELinux
        // 禁止从可写数据目录执行文件（W^X 限制），而本 App 的核心能力恰恰是在数据
        // 目录里运行下载来的 Linux 环境（proot + rootfs）。targetSdk 28 是合法的
        // 旧规则选择，同类免 root Linux 环境产品均采用同一策略。
        // 🚫 未经架构前提重新评估，任何人不得上调此值。
        targetSdk = 28
        // 最低安装门槛：安卓 15（API 35，用户第四批指定）。低于此版本安装器直接拒绝，
        // MainActivity 另有运行时兜底提示（双保险，应对旁加载极端场景）。
        minSdk = 35
        versionCode = 9
        versionName = "0.7.0"

        ndk {
            // 自研 JNI 库只编真机 arm64 与模拟器 x86_64（设计文档 §4 的 ABI 策略）
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // R8 字节码优化 + 资源收缩（用户第四批后追加）：显著减小体积与内存占用。
            // JNI / JS 桥的 keep 规则见 proguard-rules.pro。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人分发渠道：release 也用 debug 签名，保证产物可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 原生库落盘为真实文件：jniLibs 里的 proot 需要被复制+chmod+execve，
    // zstd-jni 的加载兜底也需要在 nativeLibraryDir 找到 libzstd-jni-*.so
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 锁定 NDK 版本（与已安装版本一致，保证本机与 CI 构建可复现）
    ndkVersion = "27.2.12479018"

    // 本机 C 代码：自研伪终端 JNI（见 src/main/cpp/，依据 POSIX 标准接口）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // RootFS 下载与解压（M1.1）：OkHttp 断点续传 + commons-compress 解 tar + zstd-jni 解 zstd
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.github.luben:zstd-jni:1.5.6-4")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}