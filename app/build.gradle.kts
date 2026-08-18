
import java.util.Properties
import java.io.FileInputStream
import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.chaquo.python")
    
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword", 
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}


android {
    namespace = "nocom.rian.copyparty"
    compileSdk = 36 
    
    // disable linter
    lint {
        checkReleaseBuilds = false
    }
        
    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "nocom.rian.copyparty"
        minSdk = 24
        targetSdk = 36  
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        chaquopy {
            defaultConfig {
                version = "3.10"
                pip {
                    install("jinja2")
                    install("argon2-cffi")
                    install("pyftpdlib")
                    install("pyopenssl==24.0.0")
                    install("paramiko==2.12.0")
                    install("cryptography==42.0.8")
                    install("bcrypt==3.1.7")
                    install("pyzmq==24.0.1")
                    install("pillow==11.0.0")
                    install("mutagen")
                    install("copyparty")
                    install("pip")
                }
            }
        }

    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 
        targetCompatibility = JavaVersion.VERSION_17 
    }
    
    androidResources {
        noCompress += listOf("py", "tar", "gz")
    }


    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        jniLibs {
            // ponytail: ffmpeg/ffprobe are CLI executables disguised as .so —
            // must be extracted to disk, not loaded from APK
            useLegacyPackaging = true
        }
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")
            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")
            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }
    
    configurations.all {
        resolutionStrategy {
            // Force the use of Kotlin stdlib 1.9.22 for all modules
            force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
    
            // Force specific AndroidX versions to avoid conflicts
            force("androidx.collection:collection:1.4.2")
            force("androidx.annotation:annotation:1.8.1")
            force("androidx.core:core-ktx:1.8.0")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
            force("androidx.collection:collection-ktx:1.4.2")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

// --- FFmpeg/FFprobe download task ---
// Downloads prebuilt arm64-v8a binaries from hzw1199/Android-FFmpeg-Prebuilt
// and places them in jniLibs with the lib*.so naming Android requires.
val ffmpegVersion = "9.0"
val ffmpegRepo = "hzw1199/Android-FFmpeg-Prebuilt"
val ffmpegBranch = "main"

val downloadFFmpeg by tasks.registering {
    val jniDir = file("src/main/jniLibs/arm64-v8a")
    val ffmpegOut = File(jniDir, "libffmpeg.so")
    val ffprobeOut = File(jniDir, "libffprobe.so")

    outputs.files(ffmpegOut, ffprobeOut)

    doLast {
        jniDir.mkdirs()
        val base = "https://raw.githubusercontent.com/$ffmpegRepo/$ffmpegBranch/ffmpeg-$ffmpegVersion/bin"
        mapOf("ffmpeg" to ffmpegOut, "ffprobe" to ffprobeOut).forEach { (bin, out) ->
            if (!out.exists()) {
                logger.lifecycle("Downloading $bin ($ffmpegVersion) -> ${out.name}")
                URI("$base/$bin").toURL().openStream().use { src ->
                    out.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadFFmpeg) }
 

dependencies {


    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.interpolator:interpolator:1.0.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

}
