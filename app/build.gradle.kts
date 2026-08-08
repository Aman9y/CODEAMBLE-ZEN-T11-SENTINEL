import java.util.Properties
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// layout.buildDirectory.set(file("D:/build/app"))

// Read local.properties so the Maps API key can be injected into the manifest
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// Mapping upload requires access to Google's Crashlytics symbols endpoint.
// Keep local/offline release builds deterministic; production builds can opt in with
// -PcrashlyticsMappingUploadEnabled=true.
val crashlyticsMappingUploadEnabled = providers.gradleProperty("crashlyticsMappingUploadEnabled")
    .map(String::toBoolean)
    .orElse(false)

fun localValue(name: String, fallback: String = ""): String =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val productionFirebaseProjectId = localValue(
    "PRODUCTION_FIREBASE_PROJECT_ID",
    "master2-dbbc1"
)
val productionFirebaseDatabaseUrl = localValue(
    "PRODUCTION_FIREBASE_DATABASE_URL",
    "https://master2-dbbc1-default-rtdb.firebaseio.com"
)
val devFirebaseProjectId = localValue(
    "DEV_FIREBASE_PROJECT_ID",
    "sentinel-development-db"
)
val devFirebaseDatabaseUrl = localValue(
    "DEV_FIREBASE_DATABASE_URL",
    "https://sentinel-development-db-default-rtdb.firebaseio.com"
)

android {
    namespace = "online.monarchlabs.sentinel"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.monarchlabs.sentinel"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject Maps API key from local.properties into AndroidManifest
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""

        buildConfigField(
            "String",
            "PRODUCTION_FIREBASE_PROJECT_ID",
            buildConfigString(productionFirebaseProjectId)
        )
        buildConfigField(
            "String",
            "PRODUCTION_FIREBASE_DATABASE_URL",
            buildConfigString(productionFirebaseDatabaseUrl)
        )

        // Cloudflare Workers Configuration
        buildConfigField("String", "CLOUDFLARE_EMAIL_WORKER_URL", buildConfigString(localValue("CLOUDFLARE_EMAIL_WORKER_URL", "")))
        buildConfigField("String", "CLOUDFLARE_PRIVACY_WORKER_URL", buildConfigString(localValue("CLOUDFLARE_PRIVACY_WORKER_URL", "")))
        buildConfigField("String", "CLOUDFLARE_CLIENT_SECRET", buildConfigString(localValue("CLOUDFLARE_CLIENT_SECRET", "")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", buildConfigString(localValue("GOOGLE_WEB_CLIENT_ID", "")))
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            buildConfigField("String", "BUILD_ENVIRONMENT", buildConfigString("development"))
            buildConfigField("String", "EXPECTED_FIREBASE_PROJECT_ID", buildConfigString(devFirebaseProjectId))
            buildConfigField("String", "EXPECTED_FIREBASE_DATABASE_URL", buildConfigString(devFirebaseDatabaseUrl))
        }

        create("production") {
            dimension = "environment"

            buildConfigField("String", "BUILD_ENVIRONMENT", buildConfigString("production"))
            buildConfigField("String", "EXPECTED_FIREBASE_PROJECT_ID", buildConfigString(productionFirebaseProjectId))
            buildConfigField("String", "EXPECTED_FIREBASE_DATABASE_URL", buildConfigString(productionFirebaseDatabaseUrl))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = crashlyticsMappingUploadEnabled.get()
            }
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += "ProtectedPermissions"
        checkReleaseBuilds = true
        abortOnError = false
    }

    // Fix Play Console "16 KB memory page sizes" error:
    // Align extracted native libraries to 16KB page boundaries (required for Android 15+)
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Material Design
    implementation("com.google.android.material:material:1.10.0")

    // Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:34.14.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-crashlytics")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // QR Code Scanning
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Charting Library
    implementation("com.github.AnyChart:AnyChart-Android:1.1.5")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    implementation("com.google.code.gson:gson:2.10.1")

    // Google Maps SDK
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    //XMLS
    implementation ("androidx.cardview:cardview:1.0.0")

    // WorkManager for reliable background uploads
    implementation("androidx.work:work-runtime:2.9.0")

    // Required by Firebase Sessions/Crashlytics initialization on Android 13+
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Appwrite SDK (forces newer DataStore version for 16kb alignment)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-core:1.1.1")
    implementation("io.appwrite:sdk-for-android:6.1.0")

    // Additional networking and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}
