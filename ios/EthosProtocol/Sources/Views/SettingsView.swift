import SwiftUI

struct SettingsView: View {
    @State private var iCloudSyncEnabled = ICloudSyncService.shared.isSyncEnabled
    @State private var reLockTimeout = ReLockTimeoutOption.current
    @State private var hapticFeedbackEnabled = HapticFeedbackService.isEnabled
    // Issue #476
    @ObservedObject private var accountManager = AccountManager.shared
    @State private var showSaveAccountAlert = false
    @State private var newAccountLabel = ""

    var body: some View {
        Form {
            Section {
                ForEach(accountManager.accounts) { account in
                    Button {
                        accountManager.switchTo(account.id)
                    } label: {
                        HStack {
                            Label(account.label, systemImage: "person.crop.circle")
                            Spacer()
                            if account.id == accountManager.activeAccountId {
                                Image(systemName: "checkmark").foregroundStyle(.blue)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
                .onDelete { offsets in
                    for index in offsets {
                        accountManager.removeAccount(accountManager.accounts[index].id)
                    }
                }
                Button(action: { showSaveAccountAlert = true }) {
                    Label("Save Current Account", systemImage: "plus.circle")
                }
            } header: {
                Text("Accounts")
            } footer: {
                // #476: see AccountManager's doc comment — switching swaps the
                // active auth session only, not per-account app data yet.
                Text("Save the account you're signed in with to switch back to it later without re-authenticating. Switching accounts does not yet separate vault tags, history, or notification settings between accounts.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .alert("Save Current Account", isPresented: $showSaveAccountAlert) {
                TextField("Label (e.g. Personal)", text: $newAccountLabel)
                Button("Save") {
                    guard !newAccountLabel.isEmpty else { return }
                    accountManager.saveCurrentSessionAsAccount(label: newAccountLabel)
                    newAccountLabel = ""
                }
                Button("Cancel", role: .cancel) { newAccountLabel = "" }
            }

            Section {
                Toggle("Sync vault associations to iCloud", isOn: $iCloudSyncEnabled)
                    .onChange(of: iCloudSyncEnabled) { _, newValue in
                        ICloudSyncService.shared.isSyncEnabled = newValue
                    }
                Text("Syncs which vaults are linked to your passkeys across your devices. Your passkey private keys are never uploaded.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("iCloud Backup")
            }

            Section {
                Picker("Re-lock After", selection: $reLockTimeout) {
                    ForEach(ReLockTimeoutOption.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .onChange(of: reLockTimeout) { _, newValue in
                    ReLockTimeoutOption.current = newValue
                }
                Text("Require Face ID again after the app has been in the background for this long.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Privacy")
            }

            Section {
                Toggle("Haptic Feedback", isOn: $hapticFeedbackEnabled)
                    .onChange(of: hapticFeedbackEnabled) { _, newValue in
                        HapticFeedbackService.isEnabled = newValue
                        HapticFeedbackService.shared.lightImpact()
                    }
                Text("Enable haptic feedback for successful actions, errors, and biometric unlock.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Feedback")
            }

            #if DEBUG
            Section {
                NavigationLink("Notification Log", destination: NotificationDebugView())
            } header: {
                Text("Debug")
            }
            #endif
        }
        .navigationTitle("Settings")
        .trackScreen("Settings")
        .sheet(isPresented: $showPINChange) {
            PINChangeView()
        }
    }
}

struct PINChangeView: View {
    @Environment(\.dismiss) var dismiss
    @State private var oldPIN = ""
    @State private var newPIN = ""
    @State private var newPINConfirm = ""
    @State private var error: String?
    @State private var isProcessing = false
    let isPINSetup = PINAuthenticationService.shared.isPINSetup()

    var newPINsMatch: Bool {
        !newPIN.isEmpty && newPIN == newPINConfirm
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.blue)
                    Text(isPINSetup ? "Change PIN" : "Set Up PIN").font(.title.bold())
                }
                .padding(.vertical, 16)

                VStack(alignment: .leading, spacing: 16) {
                    if isPINSetup {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Current PIN").font(.caption.bold()).foregroundStyle(.secondary)
                            SecureField("PIN", text: $oldPIN)
                                .textContentType(.oneTimeCode)
                                .keyboardType(.numberPad)
                                .font(.system(size: 18, weight: .medium, design: .monospaced))
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: 150)
                                .padding(12)
                                .background(Color(.systemGray6))
                                .cornerRadius(8)
                                .disabled(isProcessing)
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("New 6-digit PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("PIN", text: $newPIN)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isProcessing)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Confirm PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("Confirm PIN", text: $newPINConfirm)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isProcessing)
                    }

                    if let error {
                        Text(error).font(.caption).foregroundStyle(.red)
                    }

                    if !newPIN.isEmpty && !newPINConfirm.isEmpty && newPIN != newPINConfirm {
                        Text("PINs do not match").font(.caption).foregroundStyle(.orange)
                    }
                }

                Spacer()

                VStack(spacing: 12) {
                    Button(action: changePIN) {
                        Label(isProcessing ? "Processing…" : "Confirm", systemImage: "checkmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!isValid || isProcessing)

                    Button(action: { dismiss() }) {
                        Text("Cancel").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isProcessing)
                }
            }
            .padding(32)
            .navigationTitle(isPINSetup ? "Change PIN" : "Set Up PIN")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var isValid: Bool {
        if isPINSetup {
            return !oldPIN.isEmpty && newPINsMatch
        } else {
            return newPINsMatch
        }
    }

    private func changePIN() {
        guard !isProcessing else { return }
        isProcessing = true
        error = nil

        do {
            if isPINSetup {
                try PINAuthenticationService.shared.verifyPIN(oldPIN)
            }
            try PINAuthenticationService.shared.resetPIN(newPIN)
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }

        isProcessing = false
    }
}
