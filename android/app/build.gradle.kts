plugins{id("com.android.application");id("org.jetbrains.kotlin.android")}
val serverUrl = (project.findProperty("ANAMUL_SERVER_URL") as String?) ?: "https://YOUR_DOMAIN"
android{namespace="com.anamul.voice";compileSdk=34
buildFeatures { buildConfig = true }
defaultConfig{applicationId="com.anamul.voice";minSdk=24;targetSdk=34;versionCode=5;versionName="2.3.0"
    buildConfigField("String", "SERVER_URL", "\"$serverUrl\"") }
buildTypes{release{isMinifyEnabled=false}}
compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
kotlinOptions{jvmTarget="17"}}
dependencies{implementation("androidx.core:core-ktx:1.13.1")
    // Shizuku (vendored jars in app/libs — Sui stripped from the API jar,
    // stub rikka.sui.Sui in source so the stock provider never touches real Sui)
    implementation(files("libs/shizuku-api.jar", "libs/shizuku-provider.jar", "libs/shizuku-aidl.jar", "libs/shizuku-shared.jar"))}
