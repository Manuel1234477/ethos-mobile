import Foundation

final class PINAuthenticationService {
    static let shared = PINAuthenticationService()
    private init() {}

    enum PINError: LocalizedError {
        case invalidPIN
        case pinNotSetup
        case pinAlreadySetup
        case tooManyAttempts(retryAfter: TimeInterval)
        case reauthenticationRequired

        var errorDescription: String? {
            switch self {
            case .invalidPIN: return "Invalid PIN. Please try again."
            case .pinNotSetup: return "PIN has not been set up."
            case .pinAlreadySetup: return "PIN is already set up."
            case .tooManyAttempts(let retryAfter):
                let seconds = Int(retryAfter.rounded(.up))
                return "Too many failed attempts. Try again in \(seconds) second\(seconds == 1 ? "" : "s")."
            case .reauthenticationRequired:
                return "Too many failed attempts. Please unlock with your device passcode or sign in again."
            }
        }
    }

    /// Number of consecutive failures after which the user must re-authenticate
    /// with the device passcode or by logging in again.
    static let reauthenticationThreshold = 10

    private let rateLimiter = OTPRateLimiter(scope: "pin-verify")

    func isPINSetup() -> Bool {
        KeychainService.shared.isPINSetup()
    }

    func setupPIN(_ pin: String) throws {
        guard !isPINSetup() else { throw PINError.pinAlreadySetup }
        guard pin.count == 6, pin.allSatisfy({ $0.isNumber }) else {
            throw PINError.invalidPIN
        }
        KeychainService.shared.savePIN(pin)
        rateLimiter.reset()
    }

    func verifyPIN(_ pin: String) throws {
        if let retryAfter = rateLimiter.retryAfter() {
            throw PINError.tooManyAttempts(retryAfter: retryAfter)
        }

        guard KeychainService.shared.verifyPIN(pin) else {
            rateLimiter.recordFailure()
            if rateLimiter.failureCount >= Self.reauthenticationThreshold {
                throw PINError.reauthenticationRequired
            }
            throw PINError.invalidPIN
        }

        // Successful unlock: clear attempt state and migrate any legacy
        // unsalted SHA-256 hash to the salted KDF format.
        rateLimiter.reset()
        KeychainService.shared.migrateLegacyPINHashIfNeeded(pin)
    }

    func resetPIN(_ newPIN: String) throws {
        guard newPIN.count == 6, newPIN.allSatisfy({ $0.isNumber }) else {
            throw PINError.invalidPIN
        }
        KeychainService.shared.deletePIN()
        KeychainService.shared.savePIN(newPIN)
        rateLimiter.reset()
    }
}