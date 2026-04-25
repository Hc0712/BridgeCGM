/**
 * Application module build file.
 *
 * IMPORTANT:
 * - This file keeps the existing dependency set and app structure.
 * - bridge_v3.1 only adds debug logging and code comments; no feature logic is intentionally changed.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.north7.bridgecgm"
    compileSdk = 34

    // ── Read feature flags from gradle.properties ──
    fun flag(name: String, defaultDebug: Boolean, defaultRelease: Boolean): Pair<Boolean, Boolean> {
        val d = project.findProperty("cgmbridge.$name.debug")
            ?.toString()?.toBooleanStrictOrNull() ?: defaultDebug
        val r = project.findProperty("cgmbridge.$name.release")
            ?.toString()?.toBooleanStrictOrNull() ?: defaultRelease
        return d to r
    }

    val (verboseDumpD,   verboseDumpR)   = flag("verboseDump",   true,  false)
    val (chartEnabledD,  chartEnabledR)  = flag("chartEnabled",  true,  true)
    val (sharingEnabledD, sharingEnabledR) = flag("sharingEnabled", false, false)

    // Per-category debug flags
    val (debugParsingD, debugParsingR) = flag("debugParsing", true, false)
    val (debugPlottingD, debugPlottingR) = flag("debugPlotting", true, false)
    val (debugKeepAliveD, debugKeepAliveR) = flag("debugKeepAlive", true, false)
    val (debugNotificationD, debugNotificationR) = flag("debugNotification", true, false)
    val (debugDatabaseD, debugDatabaseR) = flag("debugDatabase", true, false)
    // Future categories
    val (debugCalibrationD, debugCalibrationR) = flag("debugCalibration", true, false)
    val (debugNightscoutD, debugNightscoutR) = flag("debugNightscout", true, false)
    val (debugSettingD, debugSettingR) = flag("debugSetting", true, false)
    val (debugStatisticsD, debugStatisticsR) = flag("debugStatistics", true, false)
    val (debugBluetoothD, debugBluetoothR) = flag("debugBluetooth", true, false)
    val (debugWifiD, debugWifiR) = flag("debugWifi", true, false)
    val (debugAlarmD, debugAlarmR) = flag("debugAlarm", true, false)

    defaultConfig {
        applicationId = "com.north7.bridgecgm"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "VERBOSE_DUMP",    "$verboseDumpR")
            buildConfigField("boolean", "CHART_ENABLED",   "$chartEnabledR")
            buildConfigField("boolean", "SHARING_ENABLED", "$sharingEnabledR")
            buildConfigField("boolean", "DEBUG_PARSING", "$debugParsingR")
            buildConfigField("boolean", "DEBUG_PLOTTING", "$debugPlottingR")
            buildConfigField("boolean", "DEBUG_KEEPALIVE", "$debugKeepAliveR")
            buildConfigField("boolean", "DEBUG_NOTIFICATION", "$debugNotificationR")
            buildConfigField("boolean", "DEBUG_DATABASE", "$debugDatabaseR")
            buildConfigField("boolean", "DEBUG_CALIBRATION", "$debugCalibrationR")
            buildConfigField("boolean", "DEBUG_NIGHTSCOUT", "$debugNightscoutR")
            buildConfigField("boolean", "DEBUG_SETTING", "$debugSettingR")
            buildConfigField("boolean", "DEBUG_STATISTICS", "$debugStatisticsR")
            buildConfigField("boolean", "DEBUG_BLUETOOTH", "$debugBluetoothR")
            buildConfigField("boolean", "DEBUG_WIFI", "$debugWifiR")
            buildConfigField("boolean", "DEBUG_ALARM", "$debugAlarmR")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "VERBOSE_DUMP",    "$verboseDumpD")
            buildConfigField("boolean", "CHART_ENABLED",   "$chartEnabledD")
            buildConfigField("boolean", "SHARING_ENABLED", "$sharingEnabledD")
            buildConfigField("boolean", "DEBUG_PARSING", "$debugParsingD")
            buildConfigField("boolean", "DEBUG_PLOTTING", "$debugPlottingD")
            buildConfigField("boolean", "DEBUG_KEEPALIVE", "$debugKeepAliveD")
            buildConfigField("boolean", "DEBUG_NOTIFICATION", "$debugNotificationD")
            buildConfigField("boolean", "DEBUG_DATABASE", "$debugDatabaseD")
            buildConfigField("boolean", "DEBUG_CALIBRATION", "$debugCalibrationD")
            buildConfigField("boolean", "DEBUG_NIGHTSCOUT", "$debugNightscoutD")
            buildConfigField("boolean", "DEBUG_SETTING", "$debugSettingD")
            buildConfigField("boolean", "DEBUG_STATISTICS", "$debugStatisticsD")
            buildConfigField("boolean", "DEBUG_BLUETOOTH", "$debugBluetoothD")
            buildConfigField("boolean", "DEBUG_WIFI", "$debugWifiD")
            buildConfigField("boolean", "DEBUG_ALARM", "$debugAlarmD")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // JSON building for broadcast payload
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.10")
}
