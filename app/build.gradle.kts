plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lazydog.english"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lazydog.english"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // debug 签名固定在仓库里，不用 AGP 默认的 ~/.android/debug.keystore：
        // 后者每台机器随机生成，多机开发时签名不一致，装包必须先卸载（D-075）。
        // 口令是 Android 公开的 debug 口令，不签 release 包，不属于密钥。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // android.util.Log 在 JVM 单测里没有实现，默认会抛 "not mocked"。
            // AI 客户端现在带日志，契约测试要能照常跑，让这类调用返回默认值即可。
            isReturnDefaultValues = true
        }
    }
}

ksp {
    // 导出 Room schema，给后续版本做迁移测试用。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.azure.speech)
    // 单词视觉记忆图片：外链缩略图要有内存/磁盘缓存和取消，自己写一遍不会更省
    // （D-071 批准的依赖新增）。
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
