import XCTest
@testable import EthosProtocol

// MARK: - #620 PIN Attempt Limiting Tests

final class PINAuthenticationServiceLockoutTests: XCTestCase {

    // ── Escalating cooldown from the OTPRateLimiter schedule ───────────────

    @MainActor
    func test_verifyPIN_below3Failures_noCooldown() async {
        let service = makeTestService()

        for _ in 1...2 { _ = await service.verifyPIN("0000") }

        XCTAssertEqual(service.failureCount, 2)
        XCTAssertFalse(service.isLockedOut,
            "Fewer than 3 failures must not trigger a cooldown")
        XCTAssertEqual(service.cooldownSecondsRemaining, 0)
    }

    @MainActor
    func test_verifyPIN_at3Failures_starts30sCooldown() async {
        let service = makeTestService()

        for _ in 1...3 { _ = await service.verifyPIN("0000") }

        XCTAssertTrue(service.isLockedOut,
            "3 failures must start a cooldown")
        XCTAssertEqual(service.cooldownSecondsRemaining,
            OTPRateLimiter.cooldownSeconds(for: 3),
            "PIN cooldown must reuse the OTPRateLimiter schedule")
        XCTAssertEqual(service.cooldownSecondsRemaining, 30)
    }

    @MainActor
    func test_verifyPIN_at4Failures_starts60sCooldown() async {
        let service = makeTestService()

        for _ in 1...4 { _ = await service.verifyPIN("0000") }

        XCTAssertTrue(service.isLockedOut)
        XCTAssertEqual(service.cooldownSecondsRemaining,
            OTPRateLimiter.cooldownSeconds(for: 4))
        XCTAssertEqual(service.cooldownSecondsRemaining, 60)
    }

    @MainActor
    func test_verifyPIN_at5Failures_starts120sCooldown() async {
        let service = makeTestService()

        for _ in 1...5 { _ = await service.verifyPIN("0000") }

        XCTAssertTrue(service.isLockedOut)
        XCTAssertEqual(service.cooldownSecondsRemaining,
            OTPRateLimiter.cooldownSeconds(for: 5))
        XCTAssertEqual(service.cooldownSecondsRemaining, 120)
    }

    @MainActor
    func test_verifyPIN_cooldownCappedAt120s() async {
        let service = makeTestService()

        for _ in 1...10 { _ = await service.verifyPIN("0000") }

        XCTAssertEqual(service.cooldownSecondsRemaining, 120,
            "Cooldown must be capped at 120 s regardless of failure count")
    }

    // ── Rejection while blocked ────────────────────────────────────────────

    @MainActor
    func test_verifyPIN_whileBlocked_rejectsEvenCorrectPIN() async {
        let service = makeTestService(pin: "1234")

        for _ in 1...3 { _ = await service.verifyPIN("0000") }
        XCTAssertTrue(service.isLockedOut, "Precondition: should be locked out")

        let result = await service.verifyPIN("1234")

        XCTAssertFalse(result,
            "Verification must be rejected while the cooldown is active, even for the correct PIN")
        XCTAssertEqual(service.failureCount, 3,
            "Rejected attempts during lockout must not increment the failure counter")
    }

    @MainActor
    func test_verifyPIN_afterCooldownElapses_allowsAttempt() async {
        var fakeNow = Date()
        let service = makeTestService(pin: "1234", now: { fakeNow })

        for _ in 1...3 { _ = await service.verifyPIN("0000") }
        XCTAssertTrue(service.isLockedOut)

        fakeNow = fakeNow.addingTimeInterval(31)

        XCTAssertFalse(service.isLockedOut,
            "Cooldown must expire once the deadline passes")
        let result = await service.verifyPIN("1234")
        XCTAssertTrue(result, "Correct PIN must be accepted after the cooldown elapses")
    }

    // ── Reset on success ───────────────────────────────────────────────────

    @MainActor
    func test_verifyPIN_success_resetsFailureCount() async {
        let service = makeTestService(pin: "1234")

        for _ in 1...2 { _ = await service.verifyPIN("0000") }
        XCTAssertEqual(service.failureCount, 2)

        let result = await service.verifyPIN("1234")

        XCTAssertTrue(result)
        XCTAssertEqual(service.failureCount, 0,
            "A successful verification must clear the failure counter")
        XCTAssertFalse(service.isLockedOut)
    }

    // ── Passcode / re-login requirement after fixed threshold ──────────────

    @MainActor
    func test_verifyPIN_belowThreshold_doesNotRequirePasscode() async {
        let service = makeTestService()

        for _ in 1...PINAuthenticationService.passcodeRequiredAfterFailures - 1 {
            _ = await service.verifyPIN("0000")
        }

        XCTAssertFalse(service.requiresPasscodeOrRelogin,
            "Passcode/re-login must not be required below the fixed failure threshold")
    }

    @MainActor
    func test_verifyPIN_atThreshold_requiresPasscodeOrRelogin() async {
        let service = makeTestService()

        for _ in 1...PINAuthenticationService.passcodeRequiredAfterFailures {
            _ = await service.verifyPIN("0000")
        }

        XCTAssertTrue(service.requiresPasscodeOrRelogin,
            "After the fixed number of failures the device passcode or a re-login must be required")
    }

    @MainActor
    func test_verifyPIN_requiresPasscode_rejectsEvenCorrectPIN() async {
        let service = makeTestService(pin: "1234")

        for _ in 1...PINAuthenticationService.passcodeRequiredAfterFailures {
            _ = await service.verifyPIN("0000")
        }
        XCTAssertTrue(service.requiresPasscodeOrRelogin, "Precondition: passcode should be required")

        let result = await service.verifyPIN("1234")

        XCTAssertFalse(result,
            "Once the passcode/re-login requirement is active, PIN verification must be rejected")
    }

    @MainActor
    func test_verifyPIN_requiresPasscode_survivesProcessDeath() async {
        let defaults = makeTestDefaults()
        let service1 = makeTestService(defaults: defaults)

        for _ in 1...PINAuthenticationService.passcodeRequiredAfterFailures {
            _ = await service1.verifyPIN("0000")
        }
        XCTAssertTrue(service1.requiresPasscodeOrRelogin)

        let service2 = makeTestService(defaults: defaults)

        XCTAssertTrue(service2.requiresPasscodeOrRelogin,
            "The passcode/re-login requirement must survive process death")
    }

    @MainActor
    func test_completePasscodeOrRelogin_clearsLockout() async {
        let service = makeTestService(pin: "1234")

        for _ in 1...PINAuthenticationService.passcodeRequiredAfterFailures {
            _ = await service.verifyPIN("0000")
        }
        XCTAssertTrue(service.requiresPasscodeOrRelogin, "Precondition: passcode should be required")

        service.completePasscodeOrRelogin()

        XCTAssertFalse(service.requiresPasscodeOrRelogin,
            "Completing the passcode/re-login flow must clear the requirement")
        XCTAssertEqual(service.failureCount, 0)
        XCTAssertFalse(service.isLockedOut)

        let result = await service.verifyPIN("1234")
        XCTAssertTrue(result, "PIN verification must work again after passcode/re-login")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private func makeTestDefaults() -> UserDefaults {
        let suiteName = "PINAuthenticationServiceLockoutTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    @MainActor
    private func makeTestService(
        pin: String = "1234",
        defaults: UserDefaults? = nil,
        now: (() -> Date)? = nil
    ) -> PINAuthenticationService {
        let service = PINAuthenticationService(
            pin: pin,
            defaults: defaults ?? makeTestDefaults(),
            now: now ?? { Date() }
        )
        service.makeTimer = { _, _ in TimerToken(cancel: {}) }
        return service
    }
}

// MARK: - #119 OTPRateLimiter Tests

final class OTPRateLimiterTests: XCTestCase {

    // ── Cooldown schedule ─────────────────────────────────────────────────

    func test_cooldownSchedule_1Failure_noCooldown() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 1), 0)
    }

    func test_cooldownSchedule_2Failures_noCooldown() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 2), 0)
    }

    func test_cooldownSchedule_3Failures_30s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 3), 30)
    }

    func test_cooldownSchedule_4Failures_60s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 4), 60)
    }

    func test_cooldownSchedule_5Failures_120s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 5), 120)
    }

    func test_cooldownSchedule_10Failures_capped120s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 10), 120,
            "Cooldown must be capped at 120 s regardless of failure count")
    }

    // ── Failure recording ─────────────────────────────────────────────────

    @MainActor
    func test_recordFailure_incrementsCount() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 1)

        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 2)
    }

    @MainActor
    func test_recordFailure_below3_noBlock() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()  // 1
        XCTAssertFalse(limiter.isBlocked)

        limiter.recordFailure()  // 2
        XCTAssertFalse(limiter.isBlocked)
    }

    @MainActor
    func test_recordFailure_at3_startsCooldown() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()  // 1
        limiter.recordFailure()  // 2
        limiter.recordFailure()  // 3

        XCTAssertTrue(limiter.isBlocked,
            "After 3 failures the limiter should block further attempts")
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 30)
    }

    @MainActor
    func test_recordFailure_at4_starts60sCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...4 { limiter.recordFailure() }

        XCTAssertTrue(limiter.isBlocked)
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 60)
    }

    @MainActor
    func test_recordFailure_at5_starts120sCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...5 { limiter.recordFailure() }

        XCTAssertTrue(limiter.isBlocked)
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 120)
    }

    // ── Reset on success ──────────────────────────────────────────────────

    @MainActor
    func test_reset_clearsFailureCount() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()
        limiter.recordFailure()
        limiter.reset()

        XCTAssertEqual(limiter.failureCount, 0,
            "reset() must clear the failure counter")
    }

    @MainActor
    func test_reset_clearsCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...3 { limiter.recordFailure() }
        XCTAssertTrue(limiter.isBlocked, "Precondition: should be blocked after 3 failures")

        limiter.reset()

        XCTAssertEqual(limiter.cooldownSecondsRemaining, 0,
            "reset() must clear the cooldown immediately")
        XCTAssertFalse(limiter.isBlocked,
            "reset() must unblock the limiter")
    }

    @MainActor
    func test_reset_allowsImmediateAttemptAfterBlock() async {
        let limiter = makeTestLimiter()

        for _ in 1...5 { limiter.recordFailure() }
        limiter.reset()

        // After reset, a new failure should start from scratch (1 failure, no cooldown)
        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 1)
        XCTAssertFalse(limiter.isBlocked,
            "First failure after reset should not trigger a cooldown")
    }

    // ── Timer injection ───────────────────────────────────────────────────

    /// Verifies that the injected timer is actually called (i.e., the limiter
    /// requests a tick-down) when a cooldown starts.
    @MainActor
    func test_cooldownStarted_firesTimer() async {
        var timerFired = false
        let limiter = makeTestLimiter(onTimerCreated: { _, _ in timerFired = true })

        for _ in 1...3 { limiter.recordFailure() }

        XCTAssertTrue(timerFired,
            "Limiter must schedule a tick-down timer when cooldown starts")
    }

    // ── Escalating sequence ───────────────────────────────────────────────

    @MainActor
    func test_escalatingSequence_cooldownIncreasesWith4thFailure() async {
        let limiter = makeTestLimiter()

        for _ in 1...3 { limiter.recordFailure() }
        let firstCooldown = limiter.cooldownSecondsRemaining

        limiter.reset()
        for _ in 1...4 { limiter.recordFailure() }
        let secondCooldown = limiter.cooldownSecondsRemaining

        XCTAssertGreaterThan(secondCooldown, firstCooldown,
            "4th failure cooldown must be longer than 3rd failure cooldown")
    }

    // ── Persistence across process death (#172) ─────────────────────────────

    @MainActor
    func test_processDeath_rehydratesFailureCountAndRecomputesCooldown() async {
        let defaults = makeTestDefaults()
        var fakeNow = Date()

        let limiter1 = makeTestLimiter(defaults: defaults, now: { fakeNow })
        for _ in 1...3 { limiter1.recordFailure() }
        XCTAssertEqual(limiter1.failureCount, 3)
        XCTAssertEqual(limiter1.cooldownSecondsRemaining, 30)

        // Simulate the process being killed and relaunched 10s later: a fresh instance
        // backed by the same storage should rehydrate, with the cooldown recomputed
        // against the current wall clock rather than reset to the full 30s.
        fakeNow = fakeNow.addingTimeInterval(10)
        let limiter2 = makeTestLimiter(defaults: defaults, now: { fakeNow })

        XCTAssertEqual(limiter2.failureCount, 3,
            "Failure count must survive process death")
        XCTAssertEqual(limiter2.cooldownSecondsRemaining, 20,
            "Cooldown must be recomputed from the persisted absolute deadline, not reset")
        XCTAssertTrue(limiter2.isBlocked)
    }

    @MainActor
    func test_processDeath_afterCooldownDeadlinePasses_unblocks() async {
        let defaults = makeTestDefaults()
        var fakeNow = Date()

        let limiter1 = makeTestLimiter(defaults: defaults, now: { fakeNow })
        for _ in 1...3 { limiter1.recordFailure() }
        XCTAssertEqual(limiter1.cooldownSecondsRemaining, 30)

        // Process was dead well past the cooldown deadline.
        fakeNow = fakeNow.addingTimeInterval(60)
        let limiter2 = makeTestLimiter(defaults: defaults, now: { fakeNow })

        XCTAssertEqual(limiter2.cooldownSecondsRemaining, 0)
        XCTAssertFalse(limiter2.isBlocked)
        XCTAssertEqual(limiter2.failureCount, 3,
            "Failure count still survives even once the cooldown itself has elapsed")
    }

    @MainActor
    func test_reset_clearsPersistedState() async {
        let defaults = makeTestDefaults()
        let limiter1 = makeTestLimiter(defaults: defaults)
        for _ in 1...3 { limiter1.recordFailure() }
        limiter1.reset()

        let limiter2 = makeTestLimiter(defaults: defaults)
        XCTAssertEqual(limiter2.failureCount, 0,
            "reset() must clear the persisted failure count, not just the in-memory value")
        XCTAssertEqual(limiter2.cooldownSecondsRemaining, 0)
    }

    @MainActor
    func test_freshInstance_withNoPriorState_startsUnblocked() async {
        let defaults = makeTestDefaults()
        let limiter = makeTestLimiter(defaults: defaults)

        XCTAssertEqual(limiter.failureCount, 0)
        XCTAssertFalse(limiter.isBlocked)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /// Creates an isolated `UserDefaults` suite so persistence tests don't leak state
    /// into each other or into `.standard`.
    private func makeTestDefaults() -> UserDefaults {
        let suiteName = "OTPRateLimiterTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    /// Creates an `OTPRateLimiter` with a no-op timer (no real `Timer` is scheduled),
    /// keeping tests synchronous and free of side effects.
    @MainActor
    private func makeTestLimiter(
        defaults: UserDefaults? = nil,
        now: (() -> Date)? = nil,
        onTimerCreated: ((_ interval: TimeInterval, _ handler: @escaping () -> Void) -> Void)? = nil
    ) -> OTPRateLimiter {
        // Always back tests with an isolated suite (never `.standard`) so persisted
        // rate-limit state can't leak between test methods or real app data.
        let limiter = OTPRateLimiter(defaults: defaults ?? makeTestDefaults(), now: now ?? { Date() })
        limiter.makeTimer = { interval, handler in
            onTimerCreated?(interval, handler)
            return TimerToken(cancel: {})
        }
        return limiter
    }
}
