import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名信息从根目录的 keystore.properties 读，那个文件不进版本库。
 *
 * 以前是直接写死在下面（storePassword = "..."），一旦推到 GitHub，
 * 等于把 keystore 密码连同 .jks 一起公开——别人可以签出同签名的包冒充正式更新。
 *
 * 没有这个文件时（例如别人刚 clone 下来）：release 产出 unsigned 包、
 * debug 回落到默认 debug 签名，仍然能正常编译调试。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseKey: File? = if (keystorePropsFile.exists()) {
    keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
} else {
    null
}
val canSignRelease = releaseKey?.exists() == true

if (!canSignRelease) {
    logger.lifecycle(
        "[草记] 未找到可用的 keystore.properties（或其中的 storeFile 不存在），" +
            "release 将不签名。需要出正式包时请参照 keystore.properties.sample 配置。"
    )
}

android {
    namespace = "com.example.mindball"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mindball"
        minSdk = 26
        targetSdk = 34
        // 允许命令行覆盖，CI 打 tag 时用：./gradlew assembleRelease -PversionName=1.23
        // versionCode 仍需手工递增（商店只认它，必须单调上涨）
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 25
        versionName = (project.findProperty("versionName") as String?) ?: "1.25"
    }

    // 正式签名（密钥与密码在 keystore.properties 里，该项目文件不入库）
    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = releaseKey
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // 上架用：显式开启 v1/v2/v3
            // v1（JAR 签名）虽然 Android 7+ 用不到，但部分商店的审核系统与加固工具仍会读它；
            // v3 支持密钥轮换，也是商店校验的常规要求。三者并存兼容性最好。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // ColorOS 适配版：关闭代码混淆以保证各厂商 ROM 上稳定运行
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 本机习惯用正式签名覆盖安装；没有密钥时回落到默认 debug 签名
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
