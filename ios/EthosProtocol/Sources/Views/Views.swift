import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject var authStore: AuthStore
    @StateObject private var timeoutIndicator = BiometricTimeoutIndicatorService.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if authStore.isAuthenticated {
                ZStack(alignment: .top) {
                    VaultListView()

                    // Biometric timeout indicator
                    if timeoutIndicator.isActive && timeoutIndicator.totalTimeoutSeconds < Int.max {
                        BiometricTimeoutIndicatorView(indicator: timeoutIndicator)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
            } else {
                AuthView()
            }

            // Re-lock gate: shown atop the vault list after the app has spent long enough
            // in the background (AuthStore.handleScenePhaseChange), independent of privacy
            // overlay below which covers *every* backgrounding regardless of duration.
            if authStore.isAuthenticated && authStore.isLocked {
                LockScreenView()
            }

            // Covers the vault list/balances the instant the app stops being active, so
            // the system's app-switcher snapshot never captures sensitive content.
            if authStore.isAuthenticated && scenePhase != .active {
                PrivacyOverlayView()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            authStore.handleScenePhaseChange(newPhase)
            // #234: retry a push-token registration that failed even after
            // NotificationService's initial retries, rather than waiting
            // indefinitely for the OS to redeliver the device token.
            if newPhase == .active {
                NotificationService.shared.retryPendingPushTokenRegistrationIfNeeded()
            }
        }
        .sheet(isPresented: $authStore.showPINSetup) {
            PINSetupView()
        }
    }
}

private struct PrivacyOverlayView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.blue)
        }
        .ignoresSafeArea()
        .transition(.opacity)
        .respectsReduceMotion()
    }
}

private struct LockScreenView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var error: String?
    @State private var isUnlocking = false
    @State private var showPINInput = false
    @State private var pinInput = ""
    @State private var pinError: String?

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 24) {
                if showPINInput {
                    pinInputView
                } else {
                    biometricView
                }
            }
            .padding(32)
        }
        .onAppear(perform: attemptBiometricUnlock)
    }

    private var biometricView: some View {
        VStack(spacing: 24) {
            Image(systemName: "faceid")
                .font(.system(size: 64))
                .foregroundStyle(.blue)
            Text("Ethos-Protocol Locked").font(.title.bold())
            if let error {
                Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
            }
            Button(action: attemptBiometricUnlock) {
                Label(isUnlocking ? "Unlocking…" : "Unlock", systemImage: "faceid")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isUnlocking)
            if PINAuthenticationService.shared.isPINSetup() {
                Button(action: { showPINInput = true }) {
                    Text("Use PIN Instead")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var pinInputView: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill")
                .font(.system(size: 64))
                .foregroundStyle(.blue)
            Text("Ethos-Protocol Locked").font(.title.bold())
            Text("Enter your 6-digit PIN").font(.subheadline).foregroundStyle(.secondary)
            SecureField("PIN", text: $pinInput)
                .textContentType(.oneTimeCode)
                .keyboardType(.numberPad)
                .font(.system(size: 20, weight: .medium, design: .monospaced))
                .multilineTextAlignment(.center)
                .frame(maxWidth: 150)
                .padding(12)
                .background(Color(.systemGray6))
                .cornerRadius(8)
                .disabled(isUnlocking)
            if let error = pinError {
                Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
            }
            Button(action: verifyPIN) {
                Label(isUnlocking ? "Verifying…" : "Unlock", systemImage: "checkmark")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isUnlocking || pinInput.count != 6)
            Button(action: { showPINInput = false; pinInput = ""; pinError = nil }) {
                Text("Back to Biometric")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private func attemptBiometricUnlock() {
        guard !isUnlocking else { return }
        isUnlocking = true
        error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Unlock Ethos-Protocol")
                HapticFeedbackService.shared.success()
                authStore.isLocked = false
            } catch {
                HapticFeedbackService.shared.error()
                self.error = error.localizedDescription
                if PINAuthenticationService.shared.isPINSetup() {
                    showPINInput = true
                }
            }
            isUnlocking = false
        }
    }

    private func verifyPIN() {
        guard !isUnlocking else { return }
        isUnlocking = true
        pinError = nil
        do {
            try PINAuthenticationService.shared.verifyPIN(pinInput)
            authStore.isLocked = false
        } catch {
            pinError = error.localizedDescription
        }
        isUnlocking = false
    }
}

// MARK: - Biometric Timeout Indicator

struct BiometricTimeoutIndicatorView: View {
    let indicator: BiometricTimeoutIndicatorService

    var body: some View {
        VStack {
            HStack {
                Image(systemName: "clock.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)

                Text("Session expires in \(formatTime(indicator.remainingSeconds))")
                    .font(.caption)
                    .foregroundStyle(.orange)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    // Background
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color(.systemGray5))

                    // Progress fill
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.orange)
                        .frame(width: geometry.size.width * indicator.progressFraction)
                }
            }
            .frame(height: 4)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .background(Color(.systemBackground))
        .border(Color(.systemGray4), width: 0.5)
    }

    private func formatTime(_ seconds: Int) -> String {
        let minutes = seconds / 60
        let secs = seconds % 60
        if minutes > 0 {
            return "\(minutes)m \(secs)s"
        } else {
            return "\(secs)s"
        }
    }
}

// MARK: - Copyable ID View

struct CopyableIDView: View {
    let fullID: String
    let displayLength: Int
    @State private var showCopiedFeedback = false

    var displayID: String {
        String(fullID.prefix(displayLength)) + "…"
    }

    var body: some View {
        HStack(spacing: 8) {
            Text(displayID).font(.headline)
            Button(action: copyToClipboard) {
                Image(systemName: "doc.on.doc")
                    .font(.caption)
                    .foregroundStyle(.blue)
            }
            .accessibilityLabel("Copy full ID")
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button(action: copyToClipboard) {
                Label("Copy Full ID", systemImage: "doc.on.doc")
            }
        }
    }

    private func copyToClipboard() {
        SensitiveClipboard.copy(fullID)
        showCopiedFeedback = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            showCopiedFeedback = false
        }
    }
}

// MARK: - Error Presentation

/// Renders an ErrorPresentation's message + recovery suggestion, plus a "Try
/// Again" button (when the error is retryable and a retry action is supplied)
/// and a "Contact Support" mail link (when the error warrants escalating).
struct ErrorActionView: View {
    let error: ErrorPresentation
    var retry: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(error.message)
                .foregroundStyle(.red)
                .font(.caption)
            if let suggestion = error.recoverySuggestion {
                Text(suggestion)
                    .foregroundStyle(.secondary)
                    .font(.caption2)
            }
            HStack(spacing: 16) {
                if error.showsRetry, let retry {
                    Button("Try Again", action: retry)
                        .font(.caption.bold())
                }
                if error.showsContactSupport {
                    Link("Contact Support", destination: SupportContact.mailURL(errorMessage: error.message))
                        .font(.caption.bold())
                }
            }
        }
        .multilineTextAlignment(.leading)
    }
}

enum SupportContact {
    static let email = "support@ethos-protocol.app"

    static func mailURL(errorMessage: String) -> URL {
        var components = URLComponents(string: "mailto:\(email)")!
        components.queryItems = [
            URLQueryItem(name: "subject", value: "Ethos-Protocol app issue"),
            URLQueryItem(name: "body", value: "I ran into this error:\n\(errorMessage)")
        ]
        return components.url ?? URL(string: "mailto:\(email)")!
    }
}

// MARK: - Auth

struct AuthView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var username = ""
    @State private var showRegister = false
    @State private var showRecovery = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)
                    .accessibilityHidden(true)
                Text("Ethos-Protocol").font(.largeTitle.bold())
                Text("Secure digital inheritance").foregroundStyle(.secondary)

                if let error = authStore.error {
                    ErrorActionView(error: error, retry: { Task { await authStore.signIn() } })
                }

                Button(action: { Task { await authStore.signIn() } }) {
                    Label("Sign in with Passkey", systemImage: "person.badge.key.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(authStore.isLoading)

                Button("Create account") { showRegister = true }
                    .foregroundStyle(.blue)

                Button("Lost your device?") { showRecovery = true }
                    .foregroundStyle(.secondary)
                    .font(.footnote)
            }
            .padding(32)
            .overlay { if authStore.isLoading { ProgressView().respectsReduceMotion() } }
            .sheet(isPresented: $showRegister) { RegisterView() }
            .sheet(isPresented: $showRecovery) { RecoverAccessView() }
        }
        .trackScreen("Auth")
    }
}

struct RegisterView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var username = ""

    private var validationResult: Result<String, UsernameValidation.ValidationError> {
        UsernameValidation.validate(username)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if case .failure(let validationError) = validationResult, !username.isEmpty {
                        Text(validationError.errorDescription ?? "Invalid username")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                if let error = authStore.error {
                    Section { ErrorActionView(error: error, retry: { Task { await authStore.register(username: username) } }) }
                }
            }
            .navigationTitle("Create Account")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Register") {
                        guard case .success(let validUsername) = validationResult else { return }
                        Task { await authStore.register(username: validUsername); dismiss() }
                    }
                    .disabled(!isValid || authStore.isLoading)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var isValid: Bool {
        if case .success = validationResult { return true }
        return false
    }
}

struct RecoverAccessView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var email = ""
    @State private var backupCode = ""
    @State private var username = ""

    private var canSubmit: Bool {
        !email.isEmpty && !backupCode.isEmpty && !username.isEmpty && !authStore.isLoading
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Email", text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                    TextField("Backup code", text: $backupCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Verify your identity")
                } footer: {
                    Text("Enter the email and backup code from when you created your account. We'll use them to link a new passkey on this device.")
                }
                Section("New Passkey") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                if let error = authStore.error {
                    Section { Text(error.message).foregroundStyle(.red).font(.caption) }
                }
            }
            .navigationTitle("Recover Access")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Link Passkey") {
                        Task {
                            await authStore.recoverAccess(email: email, backupCode: backupCode, username: username)
                            if authStore.isAuthenticated { dismiss() }
                        }
                    }
                    .disabled(!canSubmit)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Vault List

struct VaultListView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @EnvironmentObject var authStore: AuthStore
    @State private var showCreate = false
    @State private var showQRScanner = false // Issue #472
    @State private var showDeepLinkSheet = false
    @State private var showSettings = false
    @State private var showFilterMenu = false
    // #118: Non-blocking jailbreak/root warning. Dismissed by the user; does not
    // block access to the app, consistent with the "secure digital inheritance" posture.
    @State private var showIntegrityWarning = IntegrityService.shared.isJailbroken

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let error = vaultStore.error {
                    ErrorActionView(error: error, retry: { Task { await vaultStore.load() } })
                        .padding()
                }
                if vaultStore.isLoading && vaultStore.vaults.isEmpty {
                    ProgressView("Loading vaults…").respectsReduceMotion()
                } else if vaultStore.vaults.isEmpty {
                    ContentUnavailableView("No Vaults", systemImage: "lock.open", description: Text("Create your first vault to get started."))
                } else {
                    VStack(spacing: 12) {
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(.secondary)
                            TextField("Search vaults", text: $vaultStore.searchText)
                                .textFieldStyle(.roundedBorder)
                            if !vaultStore.searchText.isEmpty {
                                Button(action: { vaultStore.searchText = "" }) {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.horizontal)

                        HStack(spacing: 12) {
                            Menu {
                                Picker("Status", selection: $vaultStore.statusFilter) {
                                    ForEach(VaultStatusFilter.allCases, id: \.self) { filter in
                                        Text(filter.rawValue).tag(filter)
                                    }
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "line.3.horizontal.decrease.circle")
                                    Text(vaultStore.statusFilter.rawValue)
                                        .font(.caption2)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .background(Color.blue.opacity(0.1))
                                .cornerRadius(6)
                            }

                            Menu {
                                Picker("Sort", selection: $vaultStore.sortOption) {
                                    ForEach(VaultSortOption.allCases, id: \.self) { option in
                                        Text(option.rawValue).tag(option)
                                    }
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.up.arrow.down")
                                    Text(vaultStore.sortOption.rawValue)
                                        .font(.caption2)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .background(Color.blue.opacity(0.1))
                                .cornerRadius(6)
                            }

                            // Issue #477: filter by tag, when any tag has been assigned.
                            let knownTags = VaultTagStore.allKnownTags()
                            if !knownTags.isEmpty {
                                Menu {
                                    Button("All Tags") { vaultStore.tagFilter = nil }
                                    ForEach(knownTags, id: \.self) { tag in
                                        Button(tag) { vaultStore.tagFilter = tag }
                                    }
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: "tag")
                                        Text(vaultStore.tagFilter ?? "All Tags")
                                            .font(.caption2)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 6)
                                    .background(Color.blue.opacity(0.1))
                                    .cornerRadius(6)
                                }
                            }

                            Spacer()
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                    }
                    .padding(.vertical, 8)
                    .background(Color(.systemBackground))

                    List {
                        if let lastSync = vaultStore.lastSyncTime {
                            HStack {
                                Label("Last sync: \(Self.formatSyncTime(lastSync))", systemImage: "clock.badge.checkmark.fill")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets())
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                        }
                        if vaultStore.filteredAndSortedVaults.isEmpty && !vaultStore.searchText.isEmpty {
                            ContentUnavailableView("No Results", systemImage: "magnifyingglass", description: Text("No vaults match your search."))
                                .listRowSeparator(.hidden)
                        } else {
                            ForEach(vaultStore.filteredAndSortedVaults) { vault in
                                NavigationLink(destination: VaultDetailView(vault: vault)) {
                                    VaultRowView(vault: vault)
                                }
                            }
                            if vaultStore.hasMorePages && vaultStore.filteredAndSortedVaults.count == vaultStore.vaults.count {
                                LoadMoreRow(isLoading: vaultStore.isLoadingMore) {
                                    Task { await vaultStore.loadMore() }
                                }
                            }
                        }
                    }
                    .refreshable { await vaultStore.load() }
                }
            }
            .navigationTitle("My Vaults")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: { showCreate = true }) { Image(systemName: "plus") }
                        .accessibilityLabel("Create new vault")
                }
                ToolbarItem(placement: .primaryAction) {
                    // Issue #472
                    Button(action: { showQRScanner = true }) { Image(systemName: "qrcode.viewfinder") }
                        .accessibilityLabel("Scan vault QR code")
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu {
                        NavigationLink(destination: SettingsView()) {
                            Label("Settings", systemImage: "gear")
                        }
                        // Issue #474
                        NavigationLink(destination: VaultMergeView()) {
                            Label("Duplicate Vaults", systemImage: "arrow.triangle.merge")
                        }
                        // Issue #475
                        NavigationLink(destination: VaultExpiryTrendsView()) {
                            Label("Expiry Trends", systemImage: "chart.bar")
                        }
                        Button("Sign Out") { Task { await authStore.signOut() } }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .task { await vaultStore.load() }
            .sheet(isPresented: $showCreate) { CreateVaultView() }
            .sheet(isPresented: $showQRScanner) { VaultQRScannerView() } // Issue #472
            .sheet(isPresented: $showDeepLinkSheet, onDismiss: { vaultStore.pendingDeepLink = nil }) {
                if let link = vaultStore.pendingDeepLink {
                    DeepLinkView(link: link)
                }
            }
            .onChange(of: vaultStore.pendingDeepLink) { _, link in
                if link != nil { showDeepLinkSheet = true }
            }
            // #118: Non-blocking jailbreak warning — dismissible by the user.
            .alert("Security Warning", isPresented: $showIntegrityWarning) {
                Button("I Understand", role: .cancel) { showIntegrityWarning = false }
            } message: {
                Text("This device appears to be jailbroken. Your vault data, passkeys, and 2FA secrets may be at greater risk. Consider using a stock device for maximum security.")
            }
        }
        .trackScreen("VaultList")
    }

    // Surfaces staleness (issue #25) and any check-ins still waiting to sync (issue #28) above
    // the vault list, so both stay visible without blocking the list itself.
    @ViewBuilder
    private var statusBanners: some View {
        if let age = vaultStore.vaultsCacheAge {
            StatusBannerView(
                text: "Offline — showing vaults from \(Self.relativeAge(age))",
                systemImage: "wifi.slash",
                color: .orange)
        }
        if vaultStore.queuedCheckInCount > 0 {
            StatusBannerView(
                text: vaultStore.queuedCheckInCount == 1
                    ? "1 check-in queued — will retry when back online"
                    : "\(vaultStore.queuedCheckInCount) check-ins queued — will retry when back online",
                systemImage: "clock.arrow.circlepath",
                color: .blue)
        }
    }

    private static func relativeAge(_ interval: TimeInterval) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(fromTimeInterval: -interval)
    }

    private static func formatSyncTime(_ date: Date) -> String {
        let now = Date()
        let interval = now.timeIntervalSince(date)

        switch interval {
        case 0..<60:
            return "just now"
        case 60..<3_600:
            let minutes = Int(interval) / 60
            return minutes == 1 ? "1 min ago" : "\(minutes) mins ago"
        case 3_600..<86_400:
            let hours = Int(interval) / 3_600
            return hours == 1 ? "1 hour ago" : "\(hours) hours ago"
        default:
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            return formatter.string(from: date)
        }
    }
}

struct StatusBannerView: View {
    let text: String
    let systemImage: String
    let color: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal)
            .padding(.vertical, 6)
            .background(color.opacity(0.1))
    }
}

/// Trailing row in VaultListView's list: a "Load More" button while a further
/// page is available, or a spinner while that page is being fetched.
struct LoadMoreRow: View {
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        HStack {
            Spacer()
            if isLoading {
                ProgressView().respectsReduceMotion()
            } else {
                Button("Load More", action: action)
                    .font(.subheadline)
            }
            Spacer()
        }
        .listRowSeparator(.hidden)
    }
}

struct VaultRowView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @State private var showDeleteConfirmation = false
    // Issue #477: VaultTagStore is a plain UserDefaults-backed store, not an
    // ObservableObject, so tags are mirrored into local @State and refreshed
    // after every mutation to drive re-rendering.
    @State private var tags: [String] = []
    @State private var showAddTag = false
    @State private var newTagText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                CopyableIDView(fullID: vault.id, displayLength: 12)
                Spacer()
                StatusBadge(status: vault.status)
            }
            Text(vault.formattedBalance)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            if vault.isExpiringSoon {
                Label("Expiring soon!", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            } else if vault.expiryRiskLevel() == .approaching {
                // #480: predicted-expiry planning hint, one tier below the
                // existing server-driven "expiring soon" banner.
                Text("Predicted check-in deadline: \(vault.predictedExpiryDate.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            if !tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            Text(tag)
                                .font(.caption2)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .onAppear { tags = VaultTagStore.tags(for: vault.id) }
        .contextMenu {
            Button { showAddTag = true } label: {
                Label("Add Tag", systemImage: "tag")
            }
            ForEach(tags, id: \.self) { tag in
                Button(role: .destructive) {
                    VaultTagStore.removeTag(tag, from: vault.id)
                    tags = VaultTagStore.tags(for: vault.id)
                } label: {
                    Label("Remove \"\(tag)\"", systemImage: "tag.slash")
                }
            }
        }
        .alert("Add Tag", isPresented: $showAddTag) {
            TextField("Tag name", text: $newTagText)
            Button("Add") {
                VaultTagStore.addTag(newTagText, to: vault.id)
                tags = VaultTagStore.tags(for: vault.id)
                newTagText = ""
            }
            Button("Cancel", role: .cancel) { newTagText = "" }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button(action: { performCheckIn() }) {
                Label("Check In", systemImage: "checkmark.circle.fill")
            }
            .tint(.green)

            Button(action: { performRefresh() }) {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .tint(.blue)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive, action: { showDeleteConfirmation = true }) {
                Label("Delete", systemImage: "trash.fill")
            }
        }
        .alert("Delete Vault", isPresented: $showDeleteConfirmation) {
            Button("Delete", role: .destructive) { performDelete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to delete this vault? This action cannot be undone.")
        }
    }

    private func performCheckIn() {
        triggerHaptic()
        Task {
            await vaultStore.checkIn(vault: vault)
        }
    }

    private func performRefresh() {
        triggerHaptic()
        Task {
            await vaultStore.load()
        }
    }

    private func performDelete() {
        triggerHaptic()
        Task {
            ifNotCancelled {
                vaultStore.error = ErrorPresentation(message: "Vault deletion is not yet implemented")
            }
        }
    }

    private func triggerHaptic() {
        let impactFeedback = UIImpactFeedbackGenerator(style: .medium)
        impactFeedback.impactOccurred()
    }
}

struct StatusBadge: View {
    let status: Vault.VaultStatus
    var body: some View {
        Text(status.rawValue.capitalized)
            .font(.caption.bold())
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
    private var color: Color {
        switch status {
        case .active:   return .green
        case .expired:  return .orange
        case .released: return .blue
        case .paused:   return .gray
        }
    }
}

// MARK: - Vault Detail

struct VaultDetailView: View {
    /// How often `refreshTTLPeriodically` polls the server, in nanoseconds (60 s).
    static let ttlRefreshInterval: UInt64 = 60_000_000_000

    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @State private var isCheckingIn = false
    @State private var biometricError: String?
    @State private var show2FASetup = false
    @State private var show2FAVerify = false
    @State private var show2FASwitch = false
    @State private var twoFactorStatus: TwoFactorStatus?
    @State private var twoFactorLoadError: String?
    @State private var showDeposit = false
    @State private var showWithdraw = false
    @State private var showManageBeneficiary = false
    @State private var showNotificationPreferences = false
    /// Server-anchored TTL baseline, reconciled on every poll and `vault_updated`
    /// push (#221, #223); the server value always wins on conflict.
    @State private var ttlCountdown: TTLCountdown? = nil
    /// Locally ticked display value, updated every second by `tickTTLLocally`
    /// between the (up to 60 s apart) reconciliations above.
    @State private var ttlRemaining: UInt64? = nil

    var body: some View {
        List {
            Section("Overview") {
                LabeledContent("Balance", value: vault.formattedBalance)
                LabeledContent("Status", value: vault.status.rawValue.capitalized)
                HStack {
                    Text("Beneficiary")
                    Spacer()
                    CopyableIDView(fullID: vault.beneficiary, displayLength: 16)
                }
                if let ttl = ttlRemaining {
                    LabeledContent("TTL Remaining", value: formatDuration(ttl))
                }
                // Issue #472
                NavigationLink(destination: VaultQRCodeView(vault: vault)) {
                    Label("Share via QR Code", systemImage: "qrcode")
                }
            }

            Section("Two-Factor Authentication") {
                if let error = twoFactorLoadError {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Failed to load 2FA status")
                            .foregroundStyle(.red)
                            .font(.subheadline.bold())
                        Text(error)
                            .foregroundStyle(.secondary)
                            .font(.caption)
                        Button(action: { Task { await load2FAStatus() } }) {
                            Label("Retry", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.bordered)
                    }
                } else if let status = twoFactorStatus {
                    if status.enabled {
                        LabeledContent("2FA", value: status.method.map { "\($0.rawValue.uppercased())" } ?? "Enabled")
                        LabeledContent("Verified", value: status.verified ? "Yes" : "No")
                        if !status.verified {
                            Button("Verify Now") { show2FAVerify = true }
                        }
                        // #225: Switch method without disabling first.
                        if status.availableMethods.count > 1 {
                            Button("Switch Method") { show2FASwitch = true }
                        }
                        Button("Disable 2FA", role: .destructive) { disable2FA() }
                    } else {
                        Button("Enable 2FA") { show2FASetup = true }
                    }
                } else {
                    ProgressView()
                        .respectsReduceMotion()
                        .task { await load2FAStatus() }
                }
            }

            Section {
                Button(action: checkIn) {
                    Label(isCheckingIn ? "Checking in…" : "Check In Now", systemImage: "checkmark.circle.fill")
                }
                .disabled(isCheckingIn || vault.status != .active)
                if let error = biometricError {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
                if vaultStore.queuedCheckInCount > 0 {
                    Label("Check-in queued — will retry automatically when back online", systemImage: "clock.arrow.circlepath")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }

            Section("Funds") {
                Button(action: { showDeposit = true }) {
                    Label("Deposit", systemImage: "plus.circle.fill")
                }
                Button(action: { showWithdraw = true }) {
                    Label("Withdraw", systemImage: "arrow.up.circle.fill")
                }
                .disabled(vault.status != .active)
            }

            Section {
                Button(action: { showManageBeneficiary = true }) {
                    Label("Manage Beneficiary", systemImage: "person.2.fill")
                }
            }

            Section {
                Button(action: { showNotificationPreferences = true }) {
                    Label("Expiry Notifications", systemImage: "bell.fill")
                }
            }

            // Issue #479: local action history and frequency stats.
            Section {
                NavigationLink(destination: VaultHistoryView(vault: vault)) {
                    Label("Action History", systemImage: "clock.arrow.circlepath")
                }
            }
        }
        .navigationTitle("Vault")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { VaultActionLog.record(.view, for: vault.id) }
        // `.task` auto-cancels when the view disappears, so this polling loop
        // (and the in-flight `getTTL` request it may be awaiting) stops cleanly
        // on navigating away instead of continuing to run in the background.
        // The real-time event subscription (#20) rides along on the same task:
        // opened before the loop starts, torn down via `defer` once it exits.
        .task {
            vaultStore.subscribeToEvents(vaultID: vault.id, socket: VaultEventSocket(baseURL: APIClient.shared.baseURL))
            defer { vaultStore.unsubscribeFromEvents() }
            async let polling: () = refreshTTLPeriodically()
            async let ticking: () = tickTTLLocally()
            _ = await (polling, ticking)
        }
        // The socket delivers `vault_updated` into `vaultStore.vaults` (see
        // subscribeToEvents), not directly to this view's local TTL state — pick
        // it up here so a push reconciles the countdown exactly like a poll does,
        // per the shared rule in shared/api-contract.md (#223).
        .onChange(of: vaultStore.vaults) { _, updated in
            if let ttl = updated.first(where: { $0.id == vault.id })?.ttlRemaining {
                reconcileTTL(ttl)
            }
        }
        .sheet(isPresented: $show2FASetup) {
            // #227: Pass available methods so setup only offers what the server allows.
            TwoFactorSetupView(
                vaultID: vault.id,
                availableMethods: twoFactorStatus?.availableMethods ?? TwoFactorMethod.allCases
            )
        }
        .sheet(isPresented: $show2FAVerify) {
            TwoFactorVerifyView(
                vaultID: vault.id,
                method: twoFactorStatus?.method ?? .totp,
                provisioningUri: nil,
                secret: nil,
                onVerified: { Task { await load2FAStatus() } }
            )
        }
        // #225: Switch method sheet — new method set up before old one is torn down.
        .sheet(isPresented: $show2FASwitch) {
            if let status = twoFactorStatus, let currentMethod = status.method {
                TwoFactorSwitchView(
                    vaultID: vault.id,
                    currentMethod: currentMethod,
                    availableMethods: status.availableMethods
                )
            }
        }
        .sheet(isPresented: $showDeposit) {
            DepositView(vault: vault)
        }
        .sheet(isPresented: $showWithdraw) {
            NavigationStack { WithdrawView(vault: vault) }
        }
        .sheet(isPresented: $showManageBeneficiary) {
            NavigationStack { ManageBeneficiaryView(vault: vault) }
        }
        .sheet(isPresented: $showNotificationPreferences) {
            NavigationStack { VaultNotificationPreferencesView(vaultID: vault.id) }
        }
        .trackScreen("VaultDetail")
    }

    private func load2FAStatus() async {
        twoFactorLoadError = nil
        do {
            let status = try await APIClient.shared.get2FAStatus(vaultID: vault.id)
            ifNotCancelled { twoFactorStatus = status }
        } catch {
            ifNotCancelled {
                twoFactorLoadError = error.localizedDescription
                twoFactorStatus = nil
            }
        }
    }

    private func refreshTTL() async {
        guard let ttl = try? await APIClient.shared.getTTL(vaultID: vault.id) else { return }
        ifNotCancelled { reconcileTTL(ttl) }
    }

    /// Polls the server TTL every 60 s for as long as the view is on screen.
    /// The `.task` modifier that calls this cancels it automatically on disappear.
    private func refreshTTLPeriodically() async {
        if let seed = vault.ttlRemaining { reconcileTTL(seed) }   // seed with value from vault list
        while !Task.isCancelled {
            await refreshTTL()
            try? await Task.sleep(nanoseconds: Self.ttlRefreshInterval)
        }
    }

    /// Ticks the displayed TTL down once a second between server reconciliations,
    /// so the countdown never visibly freezes while waiting on the next 60 s poll
    /// or `vault_updated` push (#221).
    private func tickTTLLocally() async {
        while !Task.isCancelled {
            if let countdown = ttlCountdown {
                ifNotCancelled { ttlRemaining = countdown.remaining() }
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    /// Applies a fresh server TTL value — from either a poll or a `vault_updated`
    /// push — as the new countdown baseline. The server value always wins over
    /// whatever the local tick had drifted to (#221, #223).
    private func reconcileTTL(_ serverValue: UInt64) {
        if ttlCountdown == nil {
            ttlCountdown = TTLCountdown(serverValue: serverValue)
        } else {
            ttlCountdown?.reconcile(serverValue: serverValue)
        }
        ttlRemaining = serverValue
    }

    // Not cancelled from `.onDisappear`: unlike the read-only TTL/2FA-status
    // polling above, this is a mutating request already in flight — cancelling
    // the Task wouldn't stop the server from processing it, it would just make
    // the app forget whether it succeeded. `ifNotCancelled` still guards the
    // state write in case cancellation reaches here some other way.
    //
    // #120: Biometric gate is enforced via Disable2FACoordinator before the API
    // call.  Disabling 2FA is at least as security-sensitive as a check-in — it
    // must require explicit user confirmation.
    private func disable2FA() {
        Task {
            do {
                try await Disable2FACoordinator().run(vaultID: vault.id)
                if !Task.isCancelled { await load2FAStatus() }
            } catch {
                ifNotCancelled { biometricError = error.localizedDescription }
            }
        }
    }

    private func checkIn() {
        biometricError = nil
        isCheckingIn = true
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm vault check-in")
                if !Task.isCancelled {
                    await vaultStore.checkIn(vault: vault)
                    HapticFeedbackService.shared.success()
                }
            } catch {
                ifNotCancelled {
                    HapticFeedbackService.shared.error()
                    biometricError = error.localizedDescription
                }
            }
            ifNotCancelled { isCheckingIn = false }
        }
    }

    private func formatDuration(_ seconds: UInt64) -> String {
        DateTimeFormatter.shared.formatDurationInSeconds(seconds)
    }
}

// MARK: - Create Vault

struct CreateVaultView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var beneficiary = ""
    @State private var intervalDays = 30.0
    @State private var isCreating = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Beneficiary") {
                    TextField("Stellar address", text: $beneficiary)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                    if !beneficiary.isEmpty && !isBeneficiaryValid {
                        Text("Enter a valid Stellar address (56 characters, starting with G).")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                Section("Check-in Interval") {
                    Slider(value: $intervalDays, in: 1...365, step: 1)
                    Text("\(Int(intervalDays)) days").foregroundStyle(.secondary)
                }
                if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
            }
            .navigationTitle("New Vault")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { create() }.disabled(!isBeneficiaryValid || isCreating)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var isBeneficiaryValid: Bool {
        StellarAddress.isValidPublicKey(beneficiary)
    }

    private func create() {
        guard isBeneficiaryValid else { return }
        isCreating = true
        Task {
            do {
                let interval = UInt64(intervalDays * 86_400)
                let vault = try await APIClient.shared.createVault(beneficiary: beneficiary, checkInInterval: interval)
                if let credentialID = KeychainService.shared.loadCredentialID() {
                    ICloudSyncService.shared.save(vaultID: vault.id, credentialID: credentialID)
                }
                await vaultStore.load()
                dismiss()
            } catch { self.error = error.localizedDescription }
            isCreating = false
        }
    }
}

// MARK: - Deposit

struct DepositView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var amountText = ""
    @State private var isDepositing = false
    @State private var error: String?

    private var amountStroops: Int64? { VaultAmount.parseStroops(amountText) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Vault") {
                    LabeledContent("Current Balance", value: vault.formattedBalance)
                }
                Section("Amount") {
                    TextField("XLM amount", text: $amountText)
                        .keyboardType(.decimalPad)
                }
                if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
            }
            .navigationTitle("Deposit")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(isDepositing ? "Depositing…" : "Deposit") { deposit() }
                        .disabled(amountStroops == nil || isDepositing)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .overlay { if isDepositing { ProgressView().respectsReduceMotion() } }
        }
    }

    private func deposit() {
        guard let amount = amountStroops else { return }
        isDepositing = true; error = nil
        Task {
            await vaultStore.deposit(vault: vault, amount: amount)
            if let storeError = vaultStore.error {
                error = storeError.message
            } else {
                dismiss()
            }
            isDepositing = false
        }
    }
}

// MARK: - Withdraw

struct WithdrawView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var amountText = ""
    @State private var isWithdrawing = false
    @State private var error: String?

    private var amountStroops: Int64? { VaultAmount.parseStroops(amountText) }

    private var isAmountValid: Bool {
        guard let amount = amountStroops else { return false }
        return VaultAmount.hasSufficientBalance(amount: amount, vaultBalance: vault.balance)
    }

    var body: some View {
        Form {
            Section("Vault") {
                LabeledContent("Available Balance", value: vault.formattedBalance)
            }
            Section("Amount") {
                TextField("XLM amount", text: $amountText)
                    .keyboardType(.decimalPad)
                if let amount = amountStroops, amount > vault.balance {
                    Text("Amount exceeds available balance.").foregroundStyle(.red).font(.caption)
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .navigationTitle("Withdraw")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isWithdrawing ? "Withdrawing…" : "Withdraw") { withdraw() }
                    .disabled(!isAmountValid || isWithdrawing)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .overlay { if isWithdrawing { ProgressView().respectsReduceMotion() } }
    }

    private func withdraw() {
        guard let amount = amountStroops else { return }
        isWithdrawing = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm vault withdrawal")
                await vaultStore.withdraw(vault: vault, amount: amount)
                if let storeError = vaultStore.error {
                    error = storeError.message
                } else {
                    dismiss()
                }
            } catch {
                self.error = error.localizedDescription
            }
            isWithdrawing = false
        }
    }
}

// MARK: - Manage Beneficiary

struct ManageBeneficiaryView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var newBeneficiary = ""
    @State private var showConfirmation = false
    @State private var isUpdating = false
    @State private var error: String?
    @State private var updated = false

    private var isAddressValid: Bool {
        BeneficiaryUpdate.isValidNewBeneficiary(newBeneficiary, currentBeneficiary: vault.beneficiary)
    }

    var body: some View {
        Group {
            if showConfirmation {
                confirmationContent
            } else {
                formContent
            }
        }
        .navigationTitle("Manage Beneficiary")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var formContent: some View {
        Form {
            Section("Current Beneficiary") {
                Text(vault.beneficiary).font(.system(.body, design: .monospaced))
            }
            Section("New Beneficiary") {
                TextField("Stellar address", text: $newBeneficiary)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Continue") { showConfirmation = true }.disabled(!isAddressValid)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private var confirmationContent: some View {
        VStack(spacing: 24) {
            Image(systemName: "person.2.fill").font(.system(size: 56)).foregroundStyle(.blue)
            Text("Confirm New Beneficiary").font(.title.bold())
            VStack(alignment: .leading, spacing: 8) {
                Text("From").foregroundStyle(.secondary).font(.caption)
                Text(vault.beneficiary).font(.system(.body, design: .monospaced))
                Text("To").foregroundStyle(.secondary).font(.caption)
                Text(newBeneficiary).font(.system(.body, design: .monospaced))
            }
            if updated {
                Label("Beneficiary Updated", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                if let error { Text(error).foregroundStyle(.red).font(.caption) }
                Button(action: confirm) {
                    Text(isUpdating ? "Updating…" : "Confirm Change").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isUpdating)
                Button("Back") { showConfirmation = false }.disabled(isUpdating)
            }
        }
        .padding(32)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    private func confirm() {
        isUpdating = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm beneficiary change")
                await vaultStore.updateBeneficiary(vault: vault, newBeneficiary: newBeneficiary)
                if let storeError = vaultStore.error {
                    error = storeError.message
                } else {
                    updated = true
                }
            } catch {
                self.error = error.localizedDescription
            }
            isUpdating = false
        }
    }
}

// MARK: - Destructive Confirmation (#220)

/// Shared confirmation UI for any irreversible, destructive vault action
/// (delete, archive, …) on either platform. The confirm button stays disabled
/// until the user types `requiredText` (typically the vault's own name/ID)
/// exactly — a plain Yes/No tap is not enough given the financial and
/// beneficiary implications of getting this wrong. `onConfirm` is only ever
/// invoked once `DestructiveConfirmation.isConfirmed` is true.
struct DestructiveConfirmationView: View {
    let title: String
    let message: String
    let requiredText: String
    var confirmTitle: String = "Delete"
    let onConfirm: () -> Void

    @State private var confirmation: DestructiveConfirmation

    init(title: String, message: String, requiredText: String,
         confirmTitle: String = "Delete", onConfirm: @escaping () -> Void) {
        self.title = title
        self.message = message
        self.requiredText = requiredText
        self.confirmTitle = confirmTitle
        self.onConfirm = onConfirm
        _confirmation = State(initialValue: DestructiveConfirmation(requiredText: requiredText))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("Type \"\(requiredText)\" to confirm.")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(requiredText, text: $confirmation.enteredText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Button(confirmTitle, role: .destructive) {
                // Guarded via confirmIfMatched, not just `.disabled` below — `.disabled`
                // only stops a tap from reaching this closure through the button's own
                // gesture recognizer; it is not what makes this safe.
                confirmation.confirmIfMatched(onConfirm)
            }
            .disabled(!confirmation.isConfirmed)
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
        .padding()
    }
}

// MARK: - 2FA Views

struct TwoFactorSetupView: View {
    let vaultID: String
    @Environment(\.dismiss) var dismiss
    @State private var selectedMethod: TwoFactorMethod = .totp
    @State private var phone = ""
    @State private var email = ""
    @State private var setupResponse: Enable2FAResponse?
    @State private var showVerify = false
    @State private var isSettingUp = false
    @State private var error: String?
    @State private var setupComplete = false
    /// #227: Available methods from TwoFactorStatus; defaults to all until known.
    var availableMethods: [TwoFactorMethod] = TwoFactorMethod.allCases

    var body: some View {
        NavigationStack {
            if let response = setupResponse {
                TwoFactorVerifyView(
                    vaultID: vaultID,
                    method: response.method,
                    provisioningUri: response.provisioningUri,
                    secret: response.secret,
                    onVerified: { setupComplete = true }
                )
            } else {
                Form {
                    Section("Authentication Method") {
                        // #227: Only show methods the server reports as available.
                        Picker("Method", selection: $selectedMethod) {
                            ForEach(availableMethods, id: \.self) { method in
                                Text(methodLabel(method)).tag(method)
                            }
                        }
                    }

                    if selectedMethod == .sms {
                        Section("SMS Number") {
                            TextField("Phone number", text: $phone)
                                .keyboardType(.phonePad)
                        }
                    }

                    if selectedMethod == .email {
                        Section("Email Address") {
                            TextField("Email", text: $email)
                                .keyboardType(.emailAddress)
                                .autocapitalization(.none)
                        }
                    }

                    if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
                }
                .navigationTitle("Enable 2FA")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Continue") { setup() }
                            .disabled(isSettingUp || !canContinue)
                    }
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
                .overlay { if isSettingUp { ProgressView().respectsReduceMotion() } }
                .onAppear {
                    // #227: Default to first available method if totp is unavailable.
                    if !availableMethods.contains(selectedMethod), let first = availableMethods.first {
                        selectedMethod = first
                    }
                }
            }
        }
        .interactiveDismissDisabled(setupComplete == false)
    }

    private var canContinue: Bool {
        switch selectedMethod {
        case .totp: return true
        case .sms:  return !phone.trimmingCharacters(in: .whitespaces).isEmpty
        case .email: return !email.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func methodLabel(_ method: TwoFactorMethod) -> String {
        switch method {
        case .totp:  return "Authenticator App (TOTP)"
        case .sms:   return "SMS Code"
        case .email: return "Email Code"
        }
    }

    private func setup() {
        isSettingUp = true; error = nil
        Task {
            do {
                let response = try await APIClient.shared.enable2FA(
                    vaultID: vaultID,
                    method: selectedMethod,
                    phone: selectedMethod == .sms ? phone : nil,
                    email: selectedMethod == .email ? email : nil
                )
                setupResponse = response
            } catch {
                self.error = error.localizedDescription
            }
            isSettingUp = false
        }
    }
}

struct TwoFactorVerifyView: View {
    let vaultID: String
    let method: TwoFactorMethod
    let provisioningUri: String?
    let secret: String?
    let onVerified: () -> Void
    @Environment(\.dismiss) var dismiss

    @State private var otp = ""
    @State private var isVerifying = false
    @State private var error: String?
    /// #226: "Remember this device for 30 days" opt-in.
    @State private var trustDevice = false
    /// #224: Shown only during initial TOTP setup.
    @State private var backupCodes: [String] = []
    @State private var showBackupCodes = false
    @State private var isGeneratingBackupCodes = false

    // #119: Escalating cooldown after repeated OTP failures. State is persisted
    // (#171), so dismissing and re-presenting this view resumes any cooldown in
    // progress instead of recreating the limiter with a zeroed failure count.
    @StateObject private var rateLimiter = OTPRateLimiter()

    private var isInitialSetup: Bool {
        provisioningUri != nil || secret != nil
    }

    var body: some View {
        if showBackupCodes {
            backupCodesView
        } else {
            verifyInputView
        }
    }

    // MARK: - OTP entry view

    private var verifyInputView: some View {
        VStack(spacing: 24) {
            Image(systemName: iconName)
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityLabel("Two-factor authentication via \(methodLabel)")

            Text(titleText).font(.title.bold())

            VStack(spacing: 8) {
                if method == .totp, let uri = provisioningUri {
                    Text("Scan this URI in your authenticator app:").foregroundStyle(.secondary)
                    Text(uri).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                    if let secret {
                        ScrollView(.horizontal, showsIndicators: false) {
                            Label(secret, systemImage: "key.fill")
                                .font(.system(.caption, design: .monospaced))
                                .lineLimit(1)
                        }
                    }
                } else if method == .totp {
                    Text("Enter the 6-digit code from your authenticator app.").foregroundStyle(.secondary)
                } else {
                    Text("A verification code has been sent to your \(methodLabel).").foregroundStyle(.secondary)
                }
            }

            TextField("Enter 6-digit code", text: $otp)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .frame(maxWidth: 200)
                .multilineTextAlignment(.center)
                .font(.title2)
                .disabled(rateLimiter.isBlocked)

            // #119: Show remaining cooldown when the user is locked out.
            if rateLimiter.isBlocked {
                Label("Too many attempts — wait \(rateLimiter.cooldownSecondsRemaining)s",
                      systemImage: "timer")
                    .font(.caption)
                    .foregroundStyle(.orange)
            } else if rateLimiter.failureCount > 0 {
                Text("\(rateLimiter.failureCount) failed attempt\(rateLimiter.failureCount == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // #226: Trust-device opt-in checkbox.
            Toggle(isOn: $trustDevice) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Remember this device")
                        .font(.subheadline)
                    Text("Skip 2FA on this device for 30 days")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)

            if let error { Text(error).foregroundStyle(.red).font(.caption) }

            Button(action: verify) {
                Label(isVerifying ? "Verifying…" : "Verify", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(otp.count != 6 || isVerifying || rateLimiter.isBlocked)
        }
        .padding(32)
        .navigationTitle("Verify 2FA")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    // MARK: - #224 Backup codes display view

    private var backupCodesView: some View {
        VStack(spacing: 24) {
            Image(systemName: "key.2.on.ring.fill")
                .font(.system(size: 56))
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            Text("Save Your Backup Codes").font(.title.bold())
            Text("Store these codes somewhere safe. Each code can be used once if you lose your authenticator app. They will not be shown again.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .font(.callout)
            if isGeneratingBackupCodes {
                ProgressView("Generating codes…").respectsReduceMotion()
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(backupCodes, id: \.self) { code in
                        Text(code)
                            .font(.system(.body, design: .monospaced).bold())
                            .padding(8)
                            .frame(maxWidth: .infinity)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(.horizontal)
            }
            Button(action: { SensitiveClipboard.copy(backupCodes.joined(separator: "\n")) }) {
                Label("Copy All Codes", systemImage: "doc.on.doc").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(backupCodes.isEmpty)
            Button(action: { onVerified(); dismiss() }) {
                Text("I've Saved My Codes").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(backupCodes.isEmpty && !isGeneratingBackupCodes)
        }
        .padding(32)
        .navigationTitle("Backup Codes")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    private var titleText: String {
        if method == .totp && isInitialSetup {
            return "Verify Setup"
        } else if method == .totp {
            return "Re-verify Authenticator"
        } else {
            return "Verify Setup"
        }
    }

    private var iconName: String {
        switch method {
        case .totp:  return "lock.shield.fill"
        case .sms:   return "message.fill"
        case .email: return "envelope.fill"
        }
    }

    private var methodLabel: String {
        switch method {
        case .totp:  return "authenticator app"
        case .sms:   return "phone"
        case .email: return "email"
        }
    }

    private func verify() {
        isVerifying = true; error = nil
        Task {
            do {
                // #226: Pass trust-device preference; handle optional token in response.
                let response = try await APIClient.shared.verify2FA(vaultID: vaultID, otp: otp, trustDevice: trustDevice)
                rateLimiter.reset()   // #119

                // #226: Persist trust token if server issued one.
                if trustDevice, let token = response.deviceTrustToken, let expiry = response.expiresAt {
                    KeychainService.shared.saveTrustToken(token, vaultID: vaultID, expiresAt: expiry)
                }

                // #224: Generate and display backup codes on initial TOTP setup.
                if method == .totp && isInitialSetup {
                    isGeneratingBackupCodes = true
                    showBackupCodes = true
                    do {
                        let codesResponse = try await APIClient.shared.generateBackupCodes(vaultID: vaultID)
                        backupCodes = codesResponse.codes
                    } catch {
                        // Non-fatal: user can regenerate later from vault settings.
                        backupCodes = []
                    }
                    isGeneratingBackupCodes = false
                } else {
                    onVerified()
                    dismiss()
                }
            } catch {
                self.error = error.localizedDescription
                rateLimiter.recordFailure()   // #119
            }
            isVerifying = false
        }
    }
}

// MARK: - Deep Link Views

struct DeepLinkView: View {
    let link: UniversalLinkRouter.DeepLink
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            switch link {
            case .vaultInvitation(let vaultID):
                VaultInvitationView(vaultID: vaultID)
            case .beneficiaryAcceptance(let vaultID, let token):
                BeneficiaryAcceptanceView(vaultID: vaultID, token: token)
            case .vaultAction(let vaultID, let action):
                VaultActionDeepLinkView(vaultID: vaultID, action: action)
            }
        }
    }
}

struct VaultInvitationView: View {
    let vaultID: String
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "envelope.open.fill")
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityHidden(true)
            Text("Vault Invitation").font(.title.bold())
            VStack(spacing: 8) {
                Text("You have been invited to a vault.")
                    .foregroundStyle(.secondary)
                CopyableIDView(fullID: vaultID, displayLength: 16)
            }
            .multilineTextAlignment(.center)
            Button("Open App") { dismiss() }
                .buttonStyle(.borderedProminent)
        }
        .padding(32)
        .navigationTitle("Invitation")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }
}

struct VaultActionDeepLinkView: View {
    let vaultID: String
    let action: UniversalLinkRouter.VaultAction
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var isProcessing = false
    @State private var error: String?
    @State private var isLoading = false
    @State private var hasAttemptedLoad = false
    @State private var showWithdrawSheet = false
    @State private var showManageBeneficiary = false

    private var vault: Vault? { vaultStore.vaults.first { $0.id == vaultID } }

    var body: some View {
        Group {
            if isLoading && vault == nil {
                ProgressView("Loading vault…")
                    .respectsReduceMotion()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                switch action {
                case .viewDetails:
                    if let vault {
                        VaultDetailView(vault: vault)
                    } else {
                        vaultNotFoundContent
                    }
                case .checkIn:
                    actionContent(
                        title: "Check In",
                        systemImage: "checkmark.circle.fill",
                        description: "Confirm check-in for vault \(vaultID.prefix(16))…"
                    ) {
                        guard let vault else { error = "Vault not found"; return }
                        isProcessing = true
                        error = nil
                        Task {
                            do {
                                try await BiometricService.shared.authenticate(reason: "Confirm vault check-in")
                                await vaultStore.checkIn(vault: vault)
                                dismiss()
                            } catch let checkInError {
                                self.error = checkInError.localizedDescription
                            }
                            isProcessing = false
                        }
                    }
                case .withdraw:
                    actionContent(
                        title: "Withdraw",
                        systemImage: "arrow.up.circle.fill",
                        description: "Withdraw funds from vault \(vaultID.prefix(16))…"
                    ) {
                        if let vault { showWithdrawSheet = true }
                        else { error = "Vault not found" }
                    }
                case .manageBeneficiary:
                    actionContent(
                        title: "Manage Beneficiary",
                        systemImage: "person.2.fill",
                        description: "Update the beneficiary for vault \(vaultID.prefix(16))…"
                    ) {
                        guard vault != nil else { error = "Vault not found"; return }
                        showManageBeneficiary = true
                    }
                }
            }
        }
        .task {
            await loadVaultIfNeeded()
        }
        .sheet(isPresented: $showWithdrawSheet) {
            if let vault {
                NavigationStack { WithdrawView(vault: vault) }
            }
        }
        .sheet(isPresented: $showManageBeneficiary) {
            if let vault {
                NavigationStack { ManageBeneficiaryView(vault: vault) }
            }
        }
    }

    private func loadVaultIfNeeded() async {
        guard !hasAttemptedLoad else { return }
        hasAttemptedLoad = true

        if vault == nil {
            isLoading = true
            await vaultStore.load()
            isLoading = false
        }
    }

    private var vaultNotFoundContent: some View {
        ContentUnavailableView(
            "Vault Not Found",
            systemImage: "lock.slash",
            description: Text("Vault \(vaultID.prefix(16))… could not be loaded.")
        )
        .navigationTitle("Vault")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }

    private func actionContent(
        title: String,
        systemImage: String,
        description: String,
        onAction: @escaping () -> Void
    ) -> some View {
        VStack(spacing: 24) {
            Image(systemName: systemImage)
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityLabel(title)
                .accessibilityHidden(false)
            Text(title).font(.title.bold())
            Text(description).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if let error { Text(error).foregroundStyle(.red).font(.caption) }
            Button(action: onAction) {
                Text(isProcessing ? "Processing…" : title).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isProcessing || (action == .checkIn && vault == nil))
        }
        .padding(32)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }
}

struct BeneficiaryAcceptanceView: View {
    let vaultID: String
    let token: String
    @Environment(\.dismiss) var dismiss
    @State private var isAccepting = false
    @State private var error: String?
    @State private var accepted = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 56))
                .foregroundStyle(.green)
                .accessibilityHidden(true)
            Text("Accept Beneficiary Role").font(.title.bold())
            VStack(spacing: 8) {
                Text("You have been nominated as a beneficiary for vault:")
                    .foregroundStyle(.secondary)
                CopyableIDView(fullID: vaultID, displayLength: 16)
            }
            .multilineTextAlignment(.center)
            if accepted {
                Label("Accepted", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                if let error { Text(error).foregroundStyle(.red).font(.caption) }
                Button(action: accept) {
                    Label(isAccepting ? "Accepting…" : "Accept", systemImage: "hand.thumbsup.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isAccepting)
            }
        }
        .padding(32)
        .navigationTitle("Beneficiary Acceptance")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }

    private func accept() {
        isAccepting = true
        Task {
            do {
                try await APIClient.shared.acceptBeneficiary(vaultID: vaultID, token: token)
                if let credentialID = KeychainService.shared.loadCredentialID() {
                    ICloudSyncService.shared.save(vaultID: vaultID, credentialID: credentialID)
                }
                accepted = true
            } catch {
                self.error = error.localizedDescription
            }
            isAccepting = false
        }
    }
}

// MARK: - PIN Setup

struct PINSetupView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var pinInput = ""
    @State private var pinConfirm = ""
    @State private var error: String?
    @State private var isSetupInProgress = false

    var pinsMatch: Bool {
        !pinInput.isEmpty && pinInput == pinConfirm
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.blue)
                    Text("Set up Your PIN").font(.title.bold())
                    Text("This 6-digit PIN will be used if biometric authentication is unavailable")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.vertical, 16)

                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Enter 6-digit PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("PIN", text: $pinInput)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isSetupInProgress)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Confirm PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("Confirm PIN", text: $pinConfirm)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isSetupInProgress)
                    }

                    if let error {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    if !pinInput.isEmpty && !pinConfirm.isEmpty && pinInput != pinConfirm {
                        Text("PINs do not match")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                Spacer()

                VStack(spacing: 12) {
                    Button(action: setupPIN) {
                        Label(isSetupInProgress ? "Setting up…" : "Continue", systemImage: "checkmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!pinsMatch || isSetupInProgress)

                    Button(action: { dismiss() }) {
                        Text("Skip for Now")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isSetupInProgress)
                }
            }
            .padding(32)
        }
    }

    private func setupPIN() {
        guard !isSetupInProgress else { return }
        isSetupInProgress = true
        error = nil

        do {
            try PINAuthenticationService.shared.setupPIN(pinInput)
            authStore.showPINSetup = false
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }

        isSetupInProgress = false
    }
}
