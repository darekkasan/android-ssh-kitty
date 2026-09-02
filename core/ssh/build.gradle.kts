plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kisshkitty.core.ssh"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // SSHJ
    implementation("net.schmizz:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
