plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
    jacoco
}

// Release signing credentials come from the environment (CI) or gradle.properties
// (local ~/.gradle/gradle.properties, never committed) — never hardcoded here. When
// any of them are absent (e.g. a plain local `assembleRelease`), the release build
// type is simply left unsigned rather than failing the build.
fun signingProp(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propName) as String?)?.takeIf { it.isNotBlank() }

// #173: Certificate pins that will be compiled into a release build. Supplied out of band
// (CI secret / local gradle.properties) so rotating a pin never needs a source change, and
// verified before release artifacts ship by .github/scripts/verify_cert_pins.py. When it is
// blank, CertificatePinner's pin set is empty, which disables pinning (#169) — there is no
// compiled-in fallback, so an unconfigured build can never pin to an unmatchable value.
val releaseCertPins = System.getenv("ETHOS_CERT_PINS")?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("ethos.certPins") as String?)?.takeIf { it.isNotBlank() }
    ?: ""

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
        buildConfigField("String", "API_BASE_URL", "\"https://api.ethos-protocol.app/v1\"")
        // Empty by default: an empty pin set disables pinning, which is what debug builds
        // pointing at a local/dev host want. Only the release build type is gated (#173).
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
        //  CVE-2026-59898-59901/59919-59921, CVE-2025-55163/58056/58057/67735).
        force("io.netty:netty-buffer:4.1.137.Final")
        force("io.netty:netty-codec:4.1.137.Final")
        force("io.netty:netty-codec-http:4.1.137.Final")
        force("io.netty:netty-codec-http2:4.1.137.Final")
        force("io.netty:netty-codec-socks:4.1.137.Final")
        force("io.netty:netty-common:4.1.137.Final")
        force("io.netty:netty-handler:4.1.137.Final")
        force("io.netty:netty-handler-proxy:4.1.137.Final")
        force("io.netty:netty-resolver:4.1.137.Final")
        force("io.netty:netty-transport:4.1.137.Final")
        force("io.netty:netty-transport-native-unix-common:4.1.137.Final")
        force("io.netty:netty-transport-native-epoll:4.1.137.Final")
        force("io.netty:netty-transport-native-kqueue:4.1.137.Final")

        // commons-io: patched for CVE-2024-47554 (XmlStreamReader CPU exhaustion)
        force("commons-io:commons-io:2.22.0")

        // protobuf-java: patched for CVE-2024-7254 (StackOverflow via nested fields)
        force("com.google.protobuf:protobuf-java:3.25.5")
        force("com.google.protobuf:protobuf-java-util:3.25.5")

        // guava: patched for CVE-2023-2976 / CVE-2020-8908 (insecure temp-file creation)
        force("com.google.guava:guava:33.6.0-jre")
        force("com.google.guava:guava:33.6.0-android")
    }
}

dependencies {
    testImplementation("org.hamcrest:hamcrest:2.2")

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websockets)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore (offline)
    implementation(libs.datastore.preferences)

    // Biometric authentication
    implementation(libs.biometric)

    // Encrypted local storage (auth token)
    implementation(libs.security.crypto)

    // Credentials (Passkey)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)

    // Firebase Messaging (push notifications)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Room (offline check-in queue)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Hilt WorkManager integration
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Provides the AndroidManifest.xml entry declaring androidx.activity.ComponentActivity
    // as a launchable activity — createComposeRule() hosts its composition in one under the
    // hood, and without this, Robolectric has nothing to resolve that launch Intent against
    // even with a real app manifest merged in (isIncludeAndroidResources above). Must be
    // debugImplementation, not testImplementation: Robolectric's unit tests reuse the debug
    // variant's merged manifest, which is built from implementation/debugImplementation
    // dependencies — testImplementation artifacts are on the test classpath but never
    // contribute to that manifest merge.
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    // @HiltAndroidTest / HiltAndroidRule, used by the instrumented tests under androidTest/.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    // Espresso accessibility-checks module + the Android Accessibility Test Framework (ATF)
    // itself, used by AccessibilityScanTest to guard minimum touch target size (and other a11y
    // checks) in CI.
    androidTestImplementation("androidx.test.espresso:espresso-accessibility:3.6.1")
    androidTestImplementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.0.0")
    // Macrobenchmark library for startup performance profiling (#322)
    androidTestImplementation(libs.benchmark.macro)
}

// JaCoCo code coverage configuration
jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    jacoco {
        isIncludeNoLocationClasses = true
    }
}

task<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.testDebugUnitTest)
    group = "Coverage"
    description = "Generate JaCoCo coverage report for unit tests"

    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }

    sourceDirectories.setFrom(
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin"
        )
    )

    classDirectories.setFrom(
        files(
            fileTree("${project.buildDir}/intermediates/classes/debug/"),
            fileTree("${project.buildDir}/tmp/kotlin-classes/debug/")
        )
    )

    executionData.setFrom(
        files(
            "${project.buildDir}/jacoco/testDebugUnitTest.exec"
        )
    )
}
