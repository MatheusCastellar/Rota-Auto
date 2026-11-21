plugins { id("com.android.application") }
android {
    namespace = "com.example.opendash"
    compileSdk = 36
    defaultConfig {
        applicationId = "br.com.matheus.rotalivre.auto"
        minSdk = 29
        targetSdk = 35
        versionCode = 51
        versionName = "0.1.50"
        testInstrumentationRunner = "com.example.opendash.auto.UpdateSmokeInstrumentation"
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Private beta APK. Signing key is generated locally by Android build tools.
    buildTypes {
        debug { isDebuggable = true }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}
tasks.withType<Test>().configureEach {
    System.getProperty("javax.net.ssl.trustStoreType")?.let { systemProperty("javax.net.ssl.trustStoreType", it) }
    System.getProperty("javax.net.ssl.trustStore")?.let { systemProperty("javax.net.ssl.trustStore", it) }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
}


