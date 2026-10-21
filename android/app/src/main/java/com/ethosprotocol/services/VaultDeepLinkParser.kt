package com.ethosprotocol.services

import android.net.Uri
import android.util.Log

enum class VaultDeepLinkAction(val pathSegment: String) {
    CHECK_IN("check-in"),
    WITHDRAW("withdraw"),
    VIEW_DETAILS("view-details"),
    MANAGE_BENEFICIARY("manage-beneficiary");

    companion object {
        fun fromPathSegment(segment: String): VaultDeepLinkAction? =
            entries.find { it.pathSegment == segment }
    }
}

/**
 * Source channel attribution for deep link origins. Used to track which channels
 * drive check-ins, beneficiary acceptances, and other deep-link-triggered actions.
 */
enum class DeepLinkSource(val value: String) {
    PUSH_NOTIFICATION("push"),
    EMAIL("email"),
    SHARE_LINK("share"),
    WIDGET("widget"),
    UNKNOWN("unknown");

    companion object {
        fun fromString(value: String?): DeepLinkSource =
            entries.find { it.value == value } ?: UNKNOWN
    }
}

data class VaultDeepLink(val vaultId: String, val action: VaultDeepLinkAction, val source: DeepLinkSource = DeepLinkSource.UNKNOWN)

/**
 * A beneficiary-acceptance universal link
 * (https://ethos-protocol.app/vaults/{vaultId}/accept?token={token}).
 *
 * [token] proves the opener is the originally invited party and is required by
 * POST /vaults/{id}/accept — see shared/api-contract.md. It is parsed here so it can
 * be forwarded all the way into the API request body (#196).
 */
data class BeneficiaryAcceptLink(val vaultId: String, val token: String)

/**
 * #258: A web-initiated account-recovery link
 * (https://ethos-protocol.app/auth/recover/link?token={token}).
 *
 * The link is emailed to the user's registered address and carries a one-time
 * [token] that pre-fills the recovery step in the app so the user does not have
 * to retype the value shown in the browser. This corresponds to
 * POST /auth/recover/link in shared/api-contract.md.
 *
 * [token] follows the same allowlist as vault IDs and acceptance tokens:
 * [A-Za-z0-9_-]{1,128}.
 */
data class RecoveryDeepLink(val token: String)

object VaultDeepLinkParser {
    /**
     * Vault IDs are only ever used to build API request paths (e.g. "/vaults/$vaultId/checkin")
     * and Compose navigation routes, so any character outside this allowlist — in particular
     * "/", "%", "?", "#" — must be rejected here. Otherwise a crafted deep link (the custom
     * scheme intent-filter accepts input from any app on the device, unauthenticated) could
     * smuggle path segments/query parameters into requests made on the user's behalf, e.g. a
     * URI whose path segment decodes to "foo/../../other-endpoint".
     */
    private val VAULT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    /**
     * True if [vaultId] is safe to interpolate into an API path / navigation route.
     *
     * This is the single boundary validator for IDs entering the app: every parser below
     * rejects IDs that fail this allowlist before they can reach [ApiClient]. ApiClient
     * additionally re-validates and percent-encodes IDs as defense in depth (#597), so a
     * caller that bypasses these parsers still cannot smuggle path/query segments.
     */
    fun isValidVaultId(vaultId: String): Boolean = VAULT_ID_PATTERN.matches(vaultId)

    /**
     * Fires once per successfully parsed deep link, carrying the action and source channel
     * for aggregate analytics. This enables attribution tracking to determine which channels
     * (push, email, share, widget) drive check-ins and other vault interactions.
     *
     * Deliberately carries only [action] and [source] — never the vault ID or raw URI — so this
     * can double as an analytics hook without becoming a privacy-sensitive log of who opened which vault.
     *
     * Event schema (kept in sync with iOS #40 so usage is comparable cross-platform):
     *   name: "vault_deep_link_opened"
     *   properties: { 
     *     action: "check-in" | "withdraw" | "view-details" | "manage-beneficiary"
     *     source: "push" | "email" | "share" | "widget" | "unknown"
     *   }
     */
    fun interface EventLogger {
        fun onDeepLinkParsed(action: VaultDeepLinkAction, source: DeepLinkSource)
    }

    private val defaultEventLogger = EventLogger { action, source ->
        // android.util.Log isn't available outside an Android runtime (e.g. plain JVM unit
        // tests), and a logging failure must never break parsing — swallow and move on.
        try {
            Log.i("VaultDeepLink", "vault_deep_link_opened action=${action.pathSegment} source=${source.value}")
        } catch (_: Throwable) {
        }
    }

    /** Overridable for tests; defaults to logging to Logcat. */
    @Volatile
    var eventLogger: EventLogger = defaultEventLogger

    /**
     * #259: The set of vault IDs owned by the currently signed-in user.
     *
     * When non-null, deep links referencing a vault not in this set are rejected
     * before any API call is made. The error is intentionally generic — it must
     * not reveal whether the vault exists — to avoid leaking vault-existence
     * information via deep-link probing.
     *
     * Set to `null` (the default) when the vault list has not been loaded yet;
     * ownership is then treated as unknown and the check is skipped (the server
     * will return 403 or 404 if the vault doesn't belong to the caller).
     *
     * VaultViewModel or MainActivity should populate this after a successful
     * listVaults() response and clear it on sign-out.
     */
    @Volatile
    var ownerVaultIds: Set<String>? = null

    /** Returns true if ownership validation should pass for [vaultId]. */
    private fun isOwnedVault(vaultId: String): Boolean {
        val owned = ownerVaultIds ?: return true   // unknown — skip check
        return vaultId in owned
    }

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a URL string or returns null if unrecognised. */
    fun parseUrl(url: String, source: DeepLinkSource = DeepLinkSource.UNKNOWN): VaultDeepLink? {
        val match = URL_PATTERN.matchEntire(url.trim()) ?: return null
        val vaultId = match.groupValues[1]
        if (!isValidVaultId(vaultId)) return null
        if (!isOwnedVault(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(match.groupValues[2]) ?: return null
        eventLogger.onDeepLinkParsed(action, source)
        return VaultDeepLink(vaultId = vaultId, action = action, source = source)
    }

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a Uri or returns null if unrecognised. */
    fun parse(uri: Uri, source: DeepLinkSource = DeepLinkSource.UNKNOWN): VaultDeepLink? {
        if (uri.scheme != "ethosprotocol" || uri.host != "vault") return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val vaultId = segments[0]
        if (!isValidVaultId(vaultId)) return null
        if (!isOwnedVault(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(segments[1]) ?: return null
        eventLogger.onDeepLinkParsed(action, source)
        return VaultDeepLink(vaultId = vaultId, action = action, source = source)
    }

    /**
     * Parses https://ethos-protocol.app/vaults/{vaultId}/accept?token={token}, returning both
     * the vault ID *and* the acceptance token, or null when the link is not a well-formed
     * acceptance link. A missing or invalid token yields null: dropping it would produce an
     * acceptance request the server rejects, which is what made the Android flow silently fail.
     */
    fun parseBeneficiaryAccept(uri: Uri): BeneficiaryAcceptLink? {
        if (uri.scheme != "https" || uri.host != "ethos-protocol.app") return null
        val segments = uri.pathSegments
        // Expect /vaults/{vaultId}/accept
        if (segments.size != 3 || segments[0] != "vaults" || segments[2] != "accept") return null
        val vaultId = segments[1].takeIf { isValidVaultId(it) } ?: return null
        // Same allowlist as vault IDs (alphanumerics, dash, underscore): the token is
        // interpolated into a navigation route before it reaches the API request body.
        val token = uri.getQueryParameter("token")?.takeIf { isValidVaultId(it) } ?: return null
        return BeneficiaryAcceptLink(vaultId = vaultId, token = token)
    }

    /**
     * #258: Parses https://ethos-protocol.app/auth/recover/link?token={token}.
     *
     * Returns a [RecoveryDeepLink] with the pre-filled recovery token so the user lands
     * directly in the "finish recovery" step rather than having to retype the value.
     *
     * Returns null when:
     *   - scheme is not https (rejects any custom-scheme forgery)
     *   - host is not ethos-protocol.app
     *   - path is not exactly /auth/recover/link
     *   - the token query parameter is missing or fails the allowlist check
     */
    fun parseRecoveryLink(uri: Uri): RecoveryDeepLink? {
        if (uri.scheme != "https" || uri.host != "ethos-protocol.app") return null
        val segments = uri.pathSegments
        // Expect /auth/recover/link — exactly three segments.
        if (segments.size != 3 || segments[0] != "auth" || segments[1] != "recover" || segments[2] != "link") return null
        val token = uri.getQueryParameter("token")?.takeIf { isValidVaultId(it) } ?: return null
        return RecoveryDeepLink(token = token)
    }

    private val URL_PATTERN = Regex("^ethosprotocol://vault/([^/]+)/([^/]+)$")
}
