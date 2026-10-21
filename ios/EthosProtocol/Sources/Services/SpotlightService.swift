import Foundation
import CoreSpotlight
import OSLog

// MARK: - SpotlightIndexing protocol seam (enables test mocking)

/// Abstracts CSSearchableIndex operations so SpotlightService can be tested
/// without a real Spotlight daemon. Production code passes `CSSearchableIndex.default()`;
/// tests supply a `MockSpotlightIndex`.
protocol SpotlightIndexing {
    func indexSearchableItems(_ items: [CSSearchableItem],
                              completionHandler: ((Error?) -> Void)?)
    func deleteSearchableItems(withIdentifiers identifiers: [String],
                               completionHandler: ((Error?) -> Void)?)
    func deleteSearchableItems(withDomainIdentifiers domainIdentifiers: [String],
                               completionHandler: ((Error?) -> Void)?)
}

extension CSSearchableIndex: SpotlightIndexing {}

// MARK: - SpotlightService

/// Donates vault metadata to CoreSpotlight so users can find their vaults via
/// Spotlight Search without opening the app first.
///
/// Each vault is indexed under the domain identifier `"com.ethosprotocol.vaults"` with
/// a unique identifier of the form `"vault-<vaultID>"`. A companion `NSUserActivity`
/// is set on the item so the system can continue the activity when the user taps the
/// Spotlight result — the app receives
/// `.onContinueUserActivity("com.ethosprotocol.viewVault")` in `EthosProtocolApp`.
final class SpotlightService {

    static let shared = SpotlightService()

    // Injectable for testing.
    var index: SpotlightIndexing = CSSearchableIndex.default()

    private init() {}

    // MARK: - Constants

    static let activityType = "com.ethosprotocol.viewVault"
    static let domainIdentifier = "com.ethosprotocol.vaults"

    /// Returns the Spotlight unique identifier for a given vault ID.
    static func uniqueIdentifier(for vaultID: String) -> String {
        "vault-\(vaultID)"
    }

    // MARK: - Logging

    private static let logger = Logger(
        subsystem: "com.ethosprotocol",
        category: "SpotlightService"
    )

    /// DEBUG-only logging routed through `LogRedactor` so vault IDs and error
    /// text are redacted and nothing is emitted in release builds.
    private static func log(_ message: String) {
        #if DEBUG
        logger.debug("\(LogRedactor.redact(message), privacy: .public)")
        #endif
    }

    // MARK: - Public API

    /// Donates a single vault to Spotlight.
    ///
    /// The item title is the vault ID (truncated to 64 characters to avoid overlong entries),
    /// and the content description summarises TTL and balance so the user can identify the
    /// vault at a glance without launching the app.
    func donate(vault: Vault) {
        let item = makeSearchableItem(for: vault)
        index.indexSearchableItems([item]) { error in
            if let error {
                // Non-fatal: Spotlight indexing can fail silently if the daemon is
                // unavailable (e.g. device storage pressure).  Log and move on.
                SpotlightService.log("[SpotlightService] Failed to index vault \(vault.id): \(error)")
            }
        }
    }

    /// Donates all vaults in `vaults` to Spotlight in a single batch, replacing any
    /// previously indexed items for the same identifiers.  Typically called after a
    /// successful vault list load.
    func donateAll(vaults: [Vault]) {
        guard !vaults.isEmpty else { return }
        let items = vaults.map { makeSearchableItem(for: $0) }
        index.indexSearchableItems(items) { error in
            if let error {
                SpotlightService.log("[SpotlightService] Failed to batch-index \(items.count) vaults: \(error)")
            }
        }
    }

    /// Removes a single vault from the Spotlight index.  Call this when a vault is
    /// deleted or transitions to an expired/released state so stale entries are not
    /// surfaced to the user.
    func revoke(vaultID: String) {
        let identifier = SpotlightService.uniqueIdentifier(for: vaultID)
        index.deleteSearchableItems(withIdentifiers: [identifier]) { error in
            if let error {
                SpotlightService.log("[SpotlightService] Failed to revoke vault \(vaultID): \(error)")
            }
        }
    }

    /// Removes *all* vault items donated by this app from the Spotlight index by
    /// deleting the entire `"com.ethosprotocol.vaults"` domain.  Useful on sign-out
    /// so another user's vaults are not discoverable via Spotlight on the same device.
    func revokeAll() {
        index.deleteSearchableItems(withDomainIdentifiers: [SpotlightService.domainIdentifier]) { error in
            if let error {
                SpotlightService.log("[SpotlightService] Failed to revoke all vaults: \(error)")
            }
        }
    }

    // MARK: - Item construction

    /// Builds a `CSSearchableItem` populated with vault metadata and a linked
    /// `NSUserActivity` so Spotlight can continue the activity when the result is tapped.
    func makeSearchableItem(for vault: Vault) -> CSSearchableItem {
        let attributeSet = makeAttributeSet(for: vault)

        let item = CSSearchableItem(
            uniqueIdentifier: SpotlightService.uniqueIdentifier(for: vault.id),
            domainIdentifier: SpotlightService.domainIdentifier,
            attributeSet: attributeSet
        )

        // Set the expiration date to match the vault's remaining TTL so stale items
        // are automatically pruned by the OS if the app is not relaunched.
        if let ttl = vault.ttlRemaining {
            item.expirationDate = Date().addingTimeInterval(TimeInterval(ttl))
        }

        return item
    }

    /// Builds the `CSSearchableItemAttributeSet` with title, description, and keywords.
    func makeAttributeSet(for vault: Vault) -> CSSearchableItemAttributeSet {
        let attributeSet = CSSearchableItemAttributeSet(contentType: .item)

        // Title: vault ID truncated to 64 characters.
        attributeSet.title = String(vault.id.prefix(64))

        // Description: TTL + balance so the user can assess urgency at a glance.
        attributeSet.contentDescription = makeDescription(for: vault)

        // Keywords improve recall when the user searches by owner or asset code.
        attributeSet.keywords = [vault.owner, vault.assetCode, "vault", "ethos"]
            .filter { !$0.isEmpty }

        // Thumbnail: use the system vault symbol so the entry has a recognisable icon.
        attributeSet.thumbnailData = nil  // Leave nil; the OS uses the app icon as fallback.

        // Wire up the NSUserActivity so tapping the result opens the vault detail view.
        attributeSet.userActivity = makeUserActivity(for: vault)

        return attributeSet
    }

    /// Constructs the `NSUserActivity` that is continued when the user taps the
    /// Spotlight result.  The activity type matches the handler registered in
    /// `EthosProtocolApp` via `.onContinueUserActivity("com.ethosprotocol.viewVault")`.
    func makeUserActivity(for vault: Vault) -> NSUserActivity {
        let activity = NSUserActivity(activityType: SpotlightService.activityType)
        activity.title = String(vault.id.prefix(64))
        activity.userInfo = ["vaultID": vault.id]
        activity.isEligibleForSearch = true
        activity.isEligibleForPrediction = true
        // Required for Handoff / Spotlight continuation.
        activity.requiredUserInfoKeys = ["vaultID"]
        activity.becomeCurrent()
        return activity
    }

    // MARK: - Helpers

    private func makeDescription(for vault: Vault) -> String {
        var parts: [String] = []

        if let ttl = vault.ttlRemaining {
            parts.append("TTL: \(formatTTL(ttl))")
        } else {
            parts.append("TTL: N/A")
        }

        parts.append("Balance: \(vault.formattedBalance)")

        return parts.joined(separator: " · ")
    }

    private func formatTTL(_ seconds: UInt64) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.day, .hour, .minute]
        formatter.unitsStyle = .abbreviated
        formatter.maximumUnitCount = 2
        return formatter.string(from: TimeInterval(seconds)) ?? "\(seconds)s"
    }
}
