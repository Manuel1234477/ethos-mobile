plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dependency.check)
    // Generates CycloneDX SBOMs for the Gradle dependency graph (issue #585).
    // Applied in the app module; declared here so the version is resolved once.
    id("org.cyclonedx.bom") version "1.9.1" apply false
}

dependencyCheck {
    failBuildOnCVSS = 7.0F
    formats = listOf("HTML", "JSON")

    // Without this, dependencyCheckAggregate scans every resolvable configuration
    // in the build, including build-tooling that never ships in the app (the
    // Kotlin compiler/daemon pulled in via kotlinCompilerClasspath, KSP's
    // annotation-processor classpath, and the emulator/UTP test-orchestration
    // jars behind androidTestUtil — which drag in unrelated netty/grpc/protobuf
    // findings). Scoping to the app module's release runtime classpath restricts
    // the scan to what actually ends up in the shipped APK.
    scanConfigurations = listOf("releaseRuntimeClasspath")

    // Without an NVD API key, the default (unkeyed) NVD feed is aggressively
    // rate-limited, so a from-scratch database sync is what makes this job slow.
    // Pinning the data directory to a known path (instead of the plugin's default
    // under GRADLE_USER_HOME) lets CI cache it across runs, so most runs only fetch
    // the incremental delta instead of resyncing the whole database.
    data {
        directory = "${rootProject.projectDir}/dependency-check-data"
    }

    // Suppress false-positive CPE matches and findings in test-tooling JARs that
    // we have no control over.  See the suppression file for detailed per-entry
    // justifications.
    suppressionFile = "${rootProject.projectDir}/dependency-check-suppressions.xml"

    // This is a Kotlin/Android project only — skip analyzers for other ecosystems
    // that don't apply here, since the plugin runs them by default whenever it
    // finds any file that even loosely matches (e.g. any *.txt, *.md).
    analyzers {
        assemblyEnabled = false
        nugetconfEnabled = false
        nuspecEnabled = false
        nodeEnabled = false
        nodeAuditEnabled = false
        retirejs { enabled = false }
        rubygemsEnabled = false
        cocoapodsEnabled = false
        swiftEnabled = false
        swiftPackageResolvedEnabled = false
        cmakeEnabled = false
        golangDepEnabled = false
        golangModEnabled = false
    }
}
