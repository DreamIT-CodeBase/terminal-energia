import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Secrets are read from local.properties (NOT committed to source control) and surfaced as
// BuildConfig fields.
//
// SECURITY: a BuildConfig string is a constant in the APK's dex — `strings` on the file finds it in
// seconds. This keeps keys out of GIT, which is worth doing, but it does NOT keep them out of the
// hands of anyone holding the APK. The only real fix is a server-side proxy that holds the key.
/**
 * The version, hoisted so `defaultConfig` and the APK file name cannot drift apart.
 *
 * [APP_VERSION_CODE] is what `AppSetup.wipeOnFreshInstall` keys on — bumping it clears the working
 * directories on the next launch — and it reaches the validation output as `app_version`, so two
 * artifacts sharing a number make those records ambiguous. Bump both together.
 */
private val APP_VERSION_CODE = 14
private val APP_VERSION_NAME = "2.3"

private val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Device API — the server that brokers all storage access. Replaced `azure.storage.connectionString`:
 * the app no longer holds the account key, only the fleet secret, which buys nothing but short-lived
 * URLs scoped to a single blob and can be revoked server-side.
 *
 * Empty fleet secret → debug upload and model download are both disabled.
 */
val deviceApiBaseUrl: String = secrets.getProperty(
    "device.api.baseUrl",
    "https://systemapi.yellowdesert-82b2825f.uksouth.azurecontainerapps.io"
)
val fleetSecret: String = secrets.getProperty("device.api.fleetSecret", "")

/** Empty when unset → the cloud OCR cross-check is disabled. */
val azureAiKey: String = secrets.getProperty("azure.ai.apiKey", "")
val azureAiEndpoint: String = secrets.getProperty(
    "azure.ai.endpoint",
    "https://chirags-ai-foundry.services.ai.azure.com/openai/v1/chat/completions"
)
val azureAiModel: String = secrets.getProperty("azure.ai.model", "gpt-6-sol")

// NOTE: `azure.anthropic.endpoint` / `azure.anthropic.model` used to be read here and surfaced as
// ANTHROPIC_ENDPOINT / ANTHROPIC_MODEL. The only thing that read them was `ClaudeOcrReader`, the
// Anthropic-Messages-shaped Stage 4 reader, which is deleted — Stage 4 reads through the Responses
// endpoint below. The keys may stay in local.properties; nothing looks at them. Restoring a second
// reader means restoring these two lines and their buildConfigFields with it.

/**
 * The Responses API on the same Foundry resource — Stage 4's live reader and Stage 3's Step F board
 * audit. A THIRD request shape on the same key: `/openai/v1/responses` takes `input` +
 * `max_output_tokens` + `reasoning`, not chat-completions' `messages`, which is why it is its own
 * endpoint rather than a path rewrite of [azureAiEndpoint]. Empty disables the audit; the region
 * naming then stands as Stage 3's Step E left it.
 */
val azureResponsesEndpoint: String = secrets.getProperty(
    "azure.ai.responsesEndpoint",
    "https://chirags-ai-foundry.services.ai.azure.com/openai/v1/responses"
)

/**
 * Azure Maps, for the Timezone/byCoordinates lookup. Empty when unset → the lookup is disabled and
 * the client returns null rather than calling out.
 */
val azureMapsKey: String = secrets.getProperty("azure.maps.key", "")

android {
    namespace = "com.terminalscout.app.prod"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.terminalscout.app.prod"
        minSdk = 24
        targetSdk = 35
        // Continues the prototype's sequence rather than restarting: the reference repo ships
        // versionCode 7 / 1.6, so this production build is the next step in that lineage. The version
        // reaches the validation output as `app_version` (ValidationStore), so two artifacts sharing a
        // number would make those records ambiguous.
        versionCode = APP_VERSION_CODE
        versionName = APP_VERSION_NAME

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEVICE_API_BASE_URL", "\"$deviceApiBaseUrl\"")
        buildConfigField("String", "FLEET_SECRET", "\"$fleetSecret\"")
        buildConfigField("String", "AZURE_AI_KEY", "\"$azureAiKey\"")
        buildConfigField("String", "AZURE_AI_ENDPOINT", "\"$azureAiEndpoint\"")
        buildConfigField("String", "AZURE_AI_MODEL", "\"$azureAiModel\"")
        buildConfigField("String", "AZURE_MAPS_KEY", "\"$azureMapsKey\"")
        buildConfigField("String", "AZURE_RESPONSES_ENDPOINT", "\"$azureResponsesEndpoint\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        // The TFLite interpreter memory-maps its model straight out of the APK, which only works if
        // the asset was stored uncompressed. Without this, model loading fails at runtime.
        noCompress += "tflite"
    }
    testOptions {
        // Plain JVM unit tests link against a stub android.jar whose methods throw by default, so any
        // code under test that logs takes a test down for a reason that has nothing to do with it.
        // Returning defaults instead makes Log a no-op, which is what a unit test wants from it.
        unitTests.isReturnDefaultValues = true
    }
    // Split the APK per CPU architecture instead of shipping one universal APK containing every
    // ABI's native libs (OpenCV/TFLite/ML Kit are large). installDebug then pushes only the slice
    // matching the connected device — a fraction of the universal size — which keeps installs small
    // and avoids INSTALL_FAILED_INSUFFICIENT_STORAGE on space-constrained emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("x86_64", "arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

/**
 * Names the APK `terminalscout-prod-<abi>-<versionName>.apk` instead of AGP's `app-<abi>-<buildType>`.
 *
 * The ABI stays in the name because [splits] produces one APK per architecture and they would
 * otherwise collide on a single file name — the last one written would win, silently.
 *
 * **The build type is deliberately NOT in the name, and that is a hazard worth stating.** The debug
 * variant is still debug-signed and still `debuggable`, which is what `run-as` needs — and `run-as` is
 * the only way to reach app-private storage on an unrooted device, so every validation workflow
 * (staging clips, reading sidecars, inspecting the outbox, resetting state) depends on it. So the
 * artifact this produces is a DEBUG build wearing a production-looking name. It is fine for the fleet's
 * own devices and for validation; it must not be handed to anyone as a release. A real release needs a
 * `signingConfig` in the `release` block, which does not exist yet — `assembleRelease` currently emits
 * an unsigned APK that cannot be installed.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            impl.outputFileName.set("terminalscout-prod-$abi-$APP_VERSION_NAME.apk")
        }
    }
}

// Kotlin 2.x replaced the deprecated `kotlinOptions { jvmTarget }` with the compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // CameraX: rear-camera live preview + on-device clip recording.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Background upload: WorkManager runs the queue drain with a network constraint (so it also
    // fires when connectivity returns); OkHttp does the HTTP.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    // Real GPS fix at capture time for the payload's location block.
    implementation(libs.play.services.location)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.objectdetection)
    implementation(libs.mlkit.objectdetection.custom)

    // Raw Interpreter implementation for the board + price-region detectors.
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    implementation(libs.opencv)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // The real org.json for unit tests. Android ships it, but the stub android.jar unit tests compile
    // against does not implement it — every method returns a default, so a JSONObject there parses
    // nothing and any test touching one is meaningless. This puts the actual implementation on the
    // unit-test classpath; it affects no APK.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
