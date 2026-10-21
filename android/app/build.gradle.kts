plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
    jacoco
    // #585: Emits a CycloneDX SBOM of the app's dependency graph so release APK/AAB
    // artifacts ship with a machine-readable inventory of their dependencies.
    id("org.cyclonedx.bom")
}

// #585: SBOM configuration. The release workflows run `cyclonedxBom` and upload the
// resulting JSON alongside the APK/AAB. Only the release runtime classpath is described
// (that is what actually ships inside the artifact), and the component identity is
// derived from the app's own versionName so the SBOM is traceable to a release.
tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    outputFormat.set("json")
    outputName.set("bom")
    destination.set(layout.buildDirectory.dir("reports/cyclonedx"))
    componentName.set("com.ethosprotocol")
    componentVersion.set("1.0.0")
}

// Release signing credentials come from the environment (CI) or gradle.properties
// (local ~/.gradle/gradle.properties, never committed) — never hardcoded here. When
// any of them are absent (e.g. a plain local `assembleRelease`), the release build
// type is simply left unsigned rather than failing the build.
fun signingProp(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propName) as String?)?.takeIf { it.isNotBlank() }

// #593: The certificate pin set is single-sourced in shared/cert-pins.json, which is the
// source of truth for both Android (this file) and iOS (project.yml -> Info.plist). The
// shared file is the compiled-in default so rotating a pin only requires editing one place;
// the CI gate (.github/scripts/check_tls_pinning.py) asserts the generated platform values
// match it. An out-of-band override (CI secret / local gradle.properties) still wins when
// set, so a pin can be rotated without a source change in an emergency.
//
// When the resolved set is blank, CertificatePinner's pin set is empty, which disables
// pinning (#169) — there is no compiled-in fallback, so an unconfigured build can never
// pin to an unmatchable value.
val sharedCertPinsFile = rootProject.file("../shared/cert-pins.json")

// Minimal JSON read of the shared pin set: the file is a flat object of
// {"sha256/<base64>": "description", ...} (see shared/cert-pins.json). We avoid pulling in
// a JSON library at configuration time by extracting the quoted keys with a regex.
fun readSharedCertPins(): String {
    if (!sharedCertPinsFile.exists()) return ""
    val json = sharedCertPinsFile.readText()
    val pins = Regex("\"([^\"]+)\"\\s*:").findAll(json)
        .map { it.groupValues[1] }
        .filter { it.startsWith("sha256/") }
        .toList()
    return pins.joinToString(",")
}

val releaseCertPins = System.getenv("ETHOS_CERT_PINS")?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("ethos.certPins") as String?)?.takeIf { it.isNotBlank() }
    ?: readSharedCertPins()

// #425: Sentry DSN for crash reporting. Supplied out of band (CI secret / local
// gradle.properties) like the signing credentials above — never hardcoded. When blank,
// crash reporting is disabled at runtime (Sentry is not initialized), so an unconfigured
// build simply reports nothing rather than shipping a placeholder DSN.
val sentryDsn = System.getenv("SENTRY_DSN")?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("sentry.dsn") as String?)?.takeIf { it.isNotBlank() }
    ?: ""

// #464: Google Play requires every upload's versionCode to be strictly higher than any
// previous one. The release pipeline (android/fastlane/Fastfile) computes it from Google
// Play (highest versionCode on any track + 1) and passes it in here; local and debug
// builds keep the default of 1.
val playVersionCode = System.getenv("ETHOS_VERSION_CODE")?.takeIf { it.isNotBlank() }?.let {
    it.toIntOrNull()?.takeIf { code -> code in 1..2_100_000_000 }
        ?: throw GradleException("ETHOS_VERSION_CODE must be an integer from 1 to 2100000000, got '$it'")
}

val releaseKeystorePath = signingProp("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = signingProp("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("ANDROID_KEY_ALIAS", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingProp("ANDROID_KEY_PASSWORD", "ANDROID_KEY_PASSWORD")
val hasReleaseSigningConfig = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.ethosprotocol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ethosprotocol"
        minSdk = 28
        targetSdk = 35
        versionCode = playVersionCode ?: 1
        versionName = "1.0.0"
        // Enable multidex to support large dependency trees that exceed the 64K method
        // reference limit per dex file (#430). minSdk = 28 (API 28+) means the platform
        // provides native multidex support — no legacy androidx.multidex library or
        // MultiDexApplication subclass is required.
        multiDexEnabled = true
        buildConfigField("String", "API_BASE_URL", "\"https://api.ethos-protocol.app/v1\"")
        // Empty by default: an empty pin set disables pinning, which is what debug builds
        // pointing at a local/dev host want. Only the release build type is gated (#173);
        // the release pin set is single-sourced from shared/cert-pins.json (#593).
        buildConfigField("String", "CERT_PINS", "\"\"")
        // #425: Crash reporting DSN. Empty by default (reporting disabled); the release
        // build type injects the out-of-band value below.
        buildConfigField("String", "SENTRY_DSN", "\"\"")
    }

    buildFeatures { compose = true; buildConfig = true }

    // Several JUnit Jupiter jars pulled in transitively by androidTest dependencies (e.g.
    // hilt-android-testing) each bundle their own copy of common META-INF license/notice
    // files, which collide when packaging the androidTest APK
    // ("6 files found with path 'META-INF/LICENSE.md'"). This project only uses JUnit 4
    // directly; these files carry no runtime behavior, so it's safe to just keep one copy.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    // Without this, Robolectric's unit-test runtime falls back to a synthetic manifest
    // with no registered activities (package "org.robolectric.default") instead of this
    // module's real merged manifest/resources — anything that needs to actually launch an
    // activity (ActivityScenarioRule, and transitively Compose's createComposeRule(), which
    // hosts its composition in a real activity even though it isn't createAndroidComposeRule)
    // fails with "Unable to resolve activity" (see robolectric/robolectric#4736).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // #631: Register the custom lint detector (UnguardedVaultIdLog) so it actually runs
    // during `lint`/`lintDebug`/`lintRelease`. The detector lives in the :lint module and
    // is wired in via `lintChecks` below; the rule itself is enabled in lint.xml.
    lint {
        // Fail the build on the vault-ID logging violation so it can't regress silently.
        // (Severity is defined by the detector; this just ensures it's treated as an error.)
        abortOnError = true
        // Keep the existing lint behavior for everything else intact.
        checkReleaseBuilds = true
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "CERT_PINS", "\"$releaseCertPins\"")
            // #425: Inject the out-of-band Sentry DSN into release builds for crash reporting.
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Staging build type: points at the staging API, keeps minification on for
        // parity with Release (catches ProGuard regressions early), but pins are
        // supplied separately via ETHOS_STAGING_CERT_PINS so the staging certificate
        // can rotate independently of production. App ID suffix (.staging) lets the
        // staging and production APKs coexist on the same device.
        create("staging") {
            initWith(getByName("release"))
            // Override API base URL to point at the staging backend.
            // Supplied at build time via STAGING_API_BASE_URL env var so it never
            // needs to be hardcoded here (CI injects it; local developers override
            // via ~/.gradle/gradle.properties: ethos.stagingApiBaseUrl=...).
            val stagingApiUrl = System.getenv("STAGING_API_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("ethos.stagingApiBaseUrl") as String?)?.takeIf { it.isNotBlank() }
                ?: "https://staging-api.ethos-protocol.app/v1"
            buildConfigField("String", "API_BASE_URL", "\"$stagingApiUrl\"")

            // Staging cert pins: empty → pinning disabled (safe for staging; staging
            // cert is separate from production and may rotate frequently during testing).
            // Falls back to the shared pin set (#593) so staging is pinned by default too.
            val stagingCertPins = System.getenv("ETHOS_STAGING_CERT_PINS")?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("ethos.stagingCertPins") as String?)?.takeIf { it.isNotBlank() }
                ?: readSharedCertPins()
            buildConfigField("String", "CERT_PINS", "\"$stagingCertPins\"")

            // Distinct application ID so the staging APK can be installed alongside
            // the production app on the same device for side-by-side testing.
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Staging builds are not signed for distribution (CI just verifies the APK
            // builds cleanly); add a signingConfig here when distributing via Firebase
            // App Distribution or a similar staging distribution channel.
        }
    }

    // #611: Room schema export. Room's KSP processor writes the generated schema JSON for
    // every @Database version into $projectDir/schemas (committed to VCS), and the
    // androidTest source set reads them back as assets so MigrationTestHelper can validate
    // each version step against the real, historical schemas.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // Pin explicitly so the Kotlin/KSP tasks always match compileOptions' Java 17
        // target regardless of which JDK is actually running the Gradle daemon —
        // without this, a build run under a JDK newer than 17 fails KSP with
        // "Inconsistent JVM-target compatibility detected" against javac's target.
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

// #611: Point Room's schema export at a committed directory so the generated JSON is
// available to MigrationTestHelper in androidTest (see the androidTest assets srcDir above).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // #631: Custom lint detector (UnguardedVaultIdLog) that flags unguarded vaultId in
    // Log.* calls. `lintChecks` makes the detector run as part of this module's lint
    // tasks without packaging it into the app. The rule is enabled in lint.xml.
    lintChecks(project(":lint"))
}

// JUnit pulls in org.hamcrest:hamcrest-core:1.3, which espresso-core (transitively required
// by compose-ui-test-junit4's Robolectric idling support) is incompatible with
// ("NoSuchMethodError: AllOf.allOf(Matcher, Matcher)") — 1.3's org.hamcrest.core.AllOf lacks
// an overload added later. org.hamcrest:hamcrest-core also exists at 2.x, but appears to keep
// hamcrest-core's original (limited) API for compatibility rather than gaining the newer
// overloads — those only live in the unified org.hamcrest:hamcrest artifact (2.1+, which
// subsumes hamcrest-core/hamcrest-library under the same org.hamcrest.core.* packages).
// Excluding hamcrest-core entirely and forcing hamcrest itself to 2.2 leaves exactly one
// org.hamcrest.core.AllOf implementation on the classpath: the unified artifact's.
//
// Security patch forces — keep transitive pulls of vulnerable libraries at known-safe versions:
//
//   Netty is pulled in transitively by Ktor's CIO engine and gRPC. The 2026 CVE wave
//   (CVE-2026-33870 through CVE-2026-59921) along with CVE-2025-24970, CVE-2023-44487
//   are all patched in 4.1.137.Final (latest stable, released 06-Aug-2026). Forcing the
//   whole io.netty group to 4.1.137.Final ensures every Netty module on the classpath
//   is at the patched version.
//
//   commons-io 2.13.0 is a transitive pull from Android build tooling. CVE-2024-47554
//   (XmlStreamReader CPU exhaustion) is fixed in 2.14.0+; 2.22.0 is the current latest.
//
//   protobuf-java 3.22.3 arrives transitively from Firebase and other Google libraries.
//   CVE-2024-7254 (StackOverflow via nested unknown fields) is fixed in 3.25.x+.
//
//   guava 28.1-jre arrives transitively from build-time test tooling (android-device-
//   provider-local). CVE-2023-2976 / CVE-2020-8908 (insecure temp-file creation) are fixed
//   in 32.0.0+. We force both -jre and -android variants so whichever is resolved wins.
configurations.all {
    exclude(group = "org.hamcrest", module = "hamcrest-core")
    resolutionStrategy {
        force("org.hamcrest:hamcrest:2.2")

        // Netty: force entire group to patched 4.1.137.Final (latest stable as of Aug 2026).
        // Covers CVE-2025-24970, CVE-2023-44487, and the full wave of 2026 CVEs
        // (CVE-2026-33870/33871, CVE-2026-41417/42578-42587, CVE-2026-44248-44893,
        //  CVE-2026-45416/45536/45673/45674, CVE-2026-46340, CVE-2026-47244/47691,
        //  CVE-2026-48006/48043/48059, CVE-2026-50010/50011/50020/50560,
        //  CVE-2026-55831/55833/55851, CVE-2026-56745/56746/56817/56819-56822,
        //  CVE-2026-59898-59901/5