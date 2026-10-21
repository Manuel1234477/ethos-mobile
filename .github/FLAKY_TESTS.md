# Flaky Tests Quarantine Log (#295)

This document tracks instrumented tests that are known to be flaky (intermittently failing despite correct underlying code). Flaky tests are temporarily disabled via `@Ignore` annotations while their root causes are being investigated and fixed.

## What is a Flaky Test?

A test is considered flaky if:
- It fails intermittently across multiple CI runs with no code changes
- The same test passes on retry without any fixes applied
- Failures are not deterministic (same input, different outcome)

Common causes:
- **Timing dependencies**: Tests that assume hard-coded delays (e.g., `Thread.sleep(1000)`)
- **Resource contention**: Emulator/device resource exhaustion (memory, CPU, I/O)
- **Device state**: Leftover app state between tests, network unavailability
- **Animation timing**: UI transitions not fully completed before assertion

## Quarantined Tests

### Android Instrumented Tests

| Test | Issue | Status | Root Cause | Retry Attempt |
|------|-------|--------|------------|---|
| (none currently) | — | — | — | — |

## Re-enabling a Quarantined Test

When a flaky test's root cause is fixed:

1. Remove the `@Ignore` annotation
2. Update this table (set Status to "Fixed" or "Re-enabled")
3. Run the test locally multiple times to verify stability:
   ```bash
   for i in {1..5}; do ./gradlew connectedDebugAndroidTest --tests MyTest; done
   ```
4. Run on CI (multiple PR runs if possible) to catch any residual flakiness
5. File a follow-up PR removing the test from this document entirely once it's stable

## Adding a New Quarantined Test

When you discover a flaky test:

1. Apply the `@Ignore` annotation with the issue reference:
   ```kotlin
   @Ignore("Flaky: <tracking issue> — [description of flakiness]")
   @Test
   fun testName() { ... }
   ```

2. File or reference a GitHub issue with:
   - Reproduction steps (if deterministic)
   - CI run logs showing the intermittent failure
   - Screenshot/logcat output if applicable

3. Add an entry to the table above

4. If the test is critical for release validation, mark Status as "Blocks release" so it's not forgotten

## CI Integration

The CI pipeline (`android-ci.yml`) currently:
- Runs `./gradlew connectedDebugAndroidTest` once per PR
- Fails fast on the first failing test
- Uploads test reports as artifacts

To improve flakiness detection in the future:
- Enable `@Retry`-annotated tests to run multiple times automatically
- Add `analyze_test_flakiness.py` to parse logs and report retry patterns
- Gate releases on all quarantined tests being fixed (prevent shipping with skipped tests)
