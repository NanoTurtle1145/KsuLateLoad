plugins { id("com.android.application") }

android {
    namespace = "cn.nanoturtle.ksu.lateload"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.nanoturtle.ksu.lateload"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
