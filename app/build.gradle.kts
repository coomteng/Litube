import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// File-content providers make the fingerprint respond to source changes with configuration caching.
val diagnosticDigest = MessageDigest.getInstance("SHA-256")
listOf(fileTree("src/main"),
    files("build.gradle.kts", "proguard-rules.pro", "proguard-r8-verification.pro", "../gradle/libs.versions.toml", "../settings.gradle.kts").asFileTree)
    .flatMap { it.files }.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
        diagnosticDigest.update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        diagnosticDigest.update(providers.fileContents(layout.projectDirectory.file(source.relativeTo(projectDir).path)).asBytes.get())
    }
val diagnosticBuildId = diagnosticDigest.digest().take(12).joinToString("") { "%02x".format(it) }

android {
    namespace = "com.hhst.youtubelite"
    compileSdk = 36

    defaultConfig {
        // Fork identity: a different application id so it installs side by side with upstream
        // Litube (com.hhst.litube). Versions 3.0.3.N were upstream 3.0.3 plus a fork revision
        // (versionCode = upstream code * 100 + N); from 3.1 the fork numbers itself, keeping
        // versionCode = major*10000 + minor*1000 + patch*100 (+ hotfix) so the sequence stays ordered.
        applicationId = "com.coomteng.litube"
        minSdk = 23
        targetSdk = 36
        versionCode = 31000
        versionName = "3.1"
        buildConfigField("String", "FORK_REPO", "\"coomteng/Litube\"")
        buildConfigField("String", "DIAGNOSTIC_BUILD_ID", "\"$diagnosticBuildId\"")
        buildConfigField("String", "DIAGNOSTIC_DEPENDENCIES", "\"Media3=${libs.versions.media3.get()};OkHttp=${libs.versions.okhttp.get()};Gson=${libs.versions.gson.get()};NewPipe=${libs.newpipe.extractor.get().version}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing from the environment (LITUBE_KEYSTORE, LITUBE_KEYSTORE_PASSWORD,
    // LITUBE_KEY_ALIAS, LITUBE_KEY_PASSWORD). Unset -> release stays unsigned, as before.
    val releaseKeystore = System.getenv("LITUBE_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("LITUBE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LITUBE_KEY_ALIAS") ?: "litube"
            keyPassword = System.getenv("LITUBE_KEY_PASSWORD") ?: System.getenv("LITUBE_KEYSTORE_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("minifiedDebug") {
            initWith(getByName("release"))
            proguardFile("proguard-r8-verification.pro")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            matchingFallbacks += listOf("release")
        }
    }

    // -PabiSplits=true emits one APK per ABI plus a universal one (release downloads);
    // the default single universal APK keeps CI and local installs unchanged.
    if (providers.gradleProperty("abiSplits").orNull == "true") {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                isUniversalApk = true
            }
        }
    }

    testBuildType = providers.gradleProperty("androidTestBuildType").orElse("debug").get()
    if (testBuildType == "minifiedDebug") {
        defaultConfig.testInstrumentationRunner = "com.hhst.youtubelite.player.sponsor.R8VerificationRunner"
        // Release-like verification must not depend on debug-only test activities.
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/r8AndroidTest/java"))
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warning += "MissingTranslation"
    }

    testOptions {
        animationsDisabled = true
        unitTests {
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "256m"
                it.maxParallelForks = 1
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

val diagnosticMappingDir = layout.buildDirectory.dir("outputs/diagnostic-mappings/$diagnosticBuildId")
listOf("release", "minifiedDebug").forEach { variant ->
    val suffix = variant.replaceFirstChar { it.uppercaseChar() }
    val archive = tasks.register<Copy>("archive${suffix}Diagnostics") {
        val directory = diagnosticMappingDir.get().asFile
        val destination = if (variant == "release") directory else directory.resolve(variant)
        val identity = diagnosticBuildId
        dependsOn("minify${suffix}WithR8")
        from(layout.buildDirectory.dir("outputs/mapping/$variant"))
        into(destination)
        doLast { destination.resolve("build-id.txt").writeText(identity + "\n") }
    }
    tasks.matching { it.name == "assemble$suffix" }.configureEach { finalizedBy(archive) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.guava)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.mmkv)
    implementation(libs.newpipe.extractor) {
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.extractor)
    implementation(libs.media3.muxer)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.nanohttpd)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("minifiedDebugImplementation", libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
