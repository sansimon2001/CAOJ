plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 草记手表端（安卓表 / Wear OS）
 *
 * 它只做三件事：开始、结束、打点。录音、转写、上传全在手机上跑。
 * 所以这个包不需要麦克风、不需要存储、不需要后台服务——权限只有网络，
 * 这也是它几乎不耗电的原因。
 */
android {
    namespace = "online.wokaka.caojiwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "online.wokaka.caojiwatch"
        // 手表普遍停在 Android 8~11（Wear OS 3 基于 API 30），26 能覆盖绝大多数
        minSdk = 26
        // 不追 34：手表上没有新 API 的诉求，低一点的行为变更更少
        targetSdk = 33
        versionCode = 1019000
        versionName = "1.19.0w"
    }

    buildTypes {
        release {
            // 侧载用：debug 签名就能装。要在商店发，自己换成正式签名
            // 手表存储小、安装也慢，所以开混淆瘦身：5.9MB → 1MB 左右
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 只用缓存里已有的几件，离线也能出包；不引 androidx.wear —— 那套要联网拉依赖，
    // 而且圆形/方形适配在这里手写几行就够（见 MainActivity 的 onApplyWindowInsets）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 这两件不是为了用，是为了把 appcompat 的传递依赖抬到缓存里有的版本：
    // 不带 material 时，appcompat 会去够 drawerlayout/customview 的 1.0.0，
    // 这台机器的缓存里只有 1.1.x —— 离线构建会直接停在 checkDebugAarMetadata
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // lifecycle / fragment 的传递依赖会去够 coroutines 1.7.3，这台机器的缓存里是 1.8.1。
    // 显式抬上来，离线构建才不会卡在这一步
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
