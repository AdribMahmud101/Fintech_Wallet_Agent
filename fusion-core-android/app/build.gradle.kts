import java.util.Properties


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// DeepInfra key for DEBUG builds only, read from the gitignored local.properties. Release builds
// never embed a key; they read it from the Keystore-wrapped store the user fills in Settings.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}


android {
    namespace = "app.fusion.wallet"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.fusion.wallet"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["main"].java.srcDir("$rootDir/../fusion-core-kotlin/src/main/kotlin")

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DEEPINFRA_KEY_DEBUG", "\"" + (localProps.getProperty("DEEPINFRA_API_KEY") ?: "") + "\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEEPINFRA_KEY_DEBUG", "\"\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Lightweight secp256k1 / RFC6979 / Keccak APIs; no global provider replacement.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class StashIconsTask : DefaultTask() {
    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun stash() {
        val src = layout.projectDirectory.file("src/main/res/drawable/ICONS.md").asFile
        val dst = layout.projectDirectory.file("build/ICONS.md.stash").asFile
        if (src.exists()) {
            dst.parentFile.mkdirs()
            src.renameTo(dst)
        }
    }
}

abstract class RestoreIconsTask : DefaultTask() {
    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun restore() {
        val src = layout.projectDirectory.file("build/ICONS.md.stash").asFile
        val dst = layout.projectDirectory.file("src/main/res/drawable/ICONS.md").asFile
        if (src.exists()) {
            dst.parentFile.mkdirs()
            src.renameTo(dst)
        }
    }
}

val stashIcons = tasks.register<StashIconsTask>("stashIcons")
val restoreIcons = tasks.register<RestoreIconsTask>("restoreIcons")

tasks.matching { it.name.contains("DebugResources") || it.name.contains("ReleaseResources") }.configureEach {
    dependsOn(stashIcons)
    finalizedBy(restoreIcons)
}
