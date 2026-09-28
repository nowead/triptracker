plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val robolectricSdk by configurations.creating
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(rootProject.file(".tools/robolectric-runtime"))
}

android {
    namespace = "com.triptracker.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.triptracker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRobolectricSdk)
            val testHome = rootProject.file(".tools/test-user-home")
            val testTemp = rootProject.file(".tools/test-temp")
            it.doFirst {
                testHome.mkdirs()
                testTemp.mkdirs()
            }
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("java.io.tmpdir", testTemp.absolutePath)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", rootProject.file(".tools/robolectric-runtime").absolutePath)
        }
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    robolectricSdk(libs.robolectric.sdk)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
