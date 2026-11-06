// START Non-FOSS component
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsPlugin
// END Non-FOSS component
import com.google.gms.googleservices.GoogleServicesPlugin
import dev.dimension.flare.buildlogic.flare
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("dev.dimension.flare.android-application")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktorfit)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.google.services) apply false
    // START Non-FOSS component
    alias(libs.plugins.firebase.crashlytics) apply false
    // END Non-FOSS component
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
}

val androidReleaseProperties = Properties().apply {
    rootProject.file("android-release.properties").inputStream().use(::load)
}

flare {
    namespace = "dev.dimension.flare"
    applicationId = androidReleaseProperties.getProperty("applicationId")
}

// START Non-FOSS component
if (project.file("google-services.json").exists()) {
    apply<GoogleServicesPlugin>()
    apply<CrashlyticsPlugin>()
}
// END Non-FOSS component

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        versionCode = androidReleaseProperties.getProperty("versionCode").toInt()
        versionName = androidReleaseProperties.getProperty("versionName")
    }

    val file = rootProject.file("signing.properties")
    val hasSigningProps = file.exists()

    signingConfigs {
        if (hasSigningProps) {
            create("flare") {
                val signingProp = Properties()
                signingProp.load(file.inputStream())
                storeFile = rootProject.file(signingProp.getProperty("storeFile"))
                storePassword = signingProp.getProperty("storePassword")
                keyAlias = signingProp.getProperty("keyAlias")
                keyPassword = signingProp.getProperty("keyPassword")
            }
        } else {
            // A separate signing config must never generate a replacement debug key.
            create("flare") {
                initWith(signingConfigs.getByName("debug"))
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigningProps) {
                signingConfig = signingConfigs.getByName("flare")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
            optimization {
                enable = true
            }
            // START Non-FOSS component
            if (project.file("google-services.json").exists()) {
                configure<CrashlyticsExtension> {
                    // Crashlytics 3.0.8 still detects the legacy minify flag.
                    mappingFileUploadEnabled = true
                }
            }
            // END Non-FOSS component
            signingConfig = signingConfigs.getByName("flare")
        }
    }

    // START Non-FOSS component
    if (project.file("google-services.json").exists()) {
        sourceSets.getByName("main").kotlin.directories.add("src/play/java")
    }
    // END Non-FOSS component
    if (!project.file("google-services.json").exists()){
        sourceSets.getByName("main").kotlin.directories.add("src/foss/java")
    }
}

abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val storeFile: RegularFileProperty

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Input
    abstract val expectedSignerSha256: Property<String>

    @TaskAction
    fun verify() {
        val keystore = KeyStore.getInstance(storeFile.get().asFile, storePassword.get().toCharArray())
        val alias = keyAlias.get()
        check(keystore.isKeyEntry(alias)) { "The existing Android signing key is missing. Restore the original keystore." }
        val certificate = checkNotNull(keystore.getCertificate(alias)) { "The Android signing certificate is missing." }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        check(fingerprint == expectedSignerSha256.get()) {
            "The Android signing certificate differs from android-release.properties. Restore the original keystore; do not replace the signer."
        }
    }
}

val releaseSigning = android.signingConfigs.getByName("flare")
val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    releaseSigning.storeFile?.let { storeFile.set(it) }
    storePassword.set(releaseSigning.storePassword.orEmpty())
    keyAlias.set(releaseSigning.keyAlias.orEmpty())
    expectedSignerSha256.set(androidReleaseProperties.getProperty("expectedSignerSha256"))
}
tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle", "assembleRelease", "bundleRelease") }
    .configureEach {
        dependsOn(verifyReleaseSigning)
    }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.kotlinx)
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.annotations)
    implementation(libs.ktorfit.lib)
    ksp(libs.ktorfit.ksp)
    implementation(libs.bundles.coil3)
    implementation(libs.bundles.coil3.extensions)
    implementation(libs.bundles.ktor)
    implementation(libs.molecule.runtime)
    implementation(libs.ksoup)
    implementation(libs.bundles.accompanist)
    lintChecks(libs.compose.lint.checks)
    implementation(libs.composeIcons.fontAwesome)
    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.bundles.media3)
    implementation(libs.swiper)
    implementation(libs.reorderable)
    implementation(libs.androidx.window)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.compose.webview)
    implementation(projects.shared)
    implementation(projects.social.bluesky)
    implementation(projects.social.fanbox)
    implementation(projects.social.mastodon)
    implementation(projects.social.misskey)
    implementation(projects.social.nostr)
    implementation(projects.social.pixiv)
    implementation(projects.social.vvo)
    implementation(projects.social.xqt)
    implementation(projects.feature.login)
    implementation(projects.feature.subscription)
    implementation(projects.feature.tab)
    implementation(projects.feature.agent)
    implementation(projects.feature.plugin)
    implementation(projects.composeUi)
    implementation(libs.androidx.splash)
    implementation(libs.materialKolor)
    implementation(libs.colorpicker.compose)
    implementation(libs.material.motion.compose)
    implementation(libs.haze)
    implementation(libs.haze.glass)
    implementation(libs.nestedScrollView)
    implementation(libs.precompose.molecule)
    implementation(libs.webkit)
    implementation(libs.bundles.navigation3)
    implementation(libs.richtext.ui.material3)
    implementation(libs.androidx.browser)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // START Non-FOSS component
    if (project.file("google-services.json").exists()) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics.ktx)
        implementation(libs.firebase.analytics.ktx)
        implementation(libs.kotlinx.coroutines.play.services)
        implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
        implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
    }
    // END Non-FOSS component

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

// START Non-FOSS component
if (project.file("google-services.json").exists()) {
    afterEvaluate {
        val uploadCrashlyticsMappingFileRelease by tasks
        val processDebugGoogleServices by tasks
        uploadCrashlyticsMappingFileRelease.dependsOn(processDebugGoogleServices)
    }
}
// END Non-FOSS component

abstract class GenerateDeepLinkManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostsFile: RegularFileProperty
    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun run() {
        val hosts = hostsFile.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
        val dataTags = hosts.joinToString("\n") { host ->
            """<data android:host="$host" />"""
        }

        manifest.get().asFile.writeText(
            """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application>
    <activity android:name="dev.dimension.flare.MainActivity" android:exported="true">
      <intent-filter android:autoVerify="false">
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
         <data android:scheme="https" />
         $dataTags
      </intent-filter>
    </activity>
  </application>
</manifest>
      """.trimIndent()
        )
    }
}

extensions.getByType(com.android.build.api.variant.AndroidComponentsExtension::class.java)
    .onVariants { variant: com.android.build.api.variant.Variant ->
        val t = tasks.register(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}DeepLinkManifest",
            GenerateDeepLinkManifestTask::class.java
        ) {
            hostsFile = project.layout.projectDirectory.file("deeplink.txt")
        }

        variant.sources.manifests.addGeneratedManifestFile(
            t,
            GenerateDeepLinkManifestTask::manifest
        )
    }

kotlin {
    sourceSets {
        all {
            languageSettings {
                optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            }
        }
    }
}
