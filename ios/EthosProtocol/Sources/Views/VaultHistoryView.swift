import SwiftUI

/// Issue #479: displays a vault's local action history and frequency statistics,
/// with CSV export via the system share sheet.
struct VaultHistoryView: View {
    let vault: Vault

    @State private var entries: [VaultActionEntry] = []
    @State private var frequencies: [VaultActionFrequency] = []

    var body: some View {
        List {
            if !frequencies.isEmpty {
                Section("Action Frequency") {
                    ForEach(frequencies, id: \.type) { freq in
                        LabeledContent(label(for: freq.type), value: "\(freq.count)")
                    }
                }
            }

            Section("History") {
                if entries.isEmpty {
                    Text("No recorded actions yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(entries) { entry in
                        HStack {
                            Label(label(for: entry.type), systemImage: icon(for: entry.type))
                            Spacer()
                            Text(entry.timestamp.formatted(date: .abbreviated, time: .shortened))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Action History")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                ShareLink(
                    item: VaultActionLog.exportCSV(for: vault.id),
                    preview: SharePreview("Vault History")
                ) {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(entries.isEmpty)
            }
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        entries = VaultActionLog.history(for: vault.id)
        frequencies = VaultActionLog.frequency(for: vault.id)
    }

    private func label(for type: VaultActionType) -> String {
        switch type {
        case .checkIn: return "Check-In"
        case .view: return "View"
        case .deposit: return "Deposit"
        case .withdraw: return "Withdraw"
        }
    }

    private func icon(for type: VaultActionType) -> String {
        switch type {
        case .checkIn: return "checkmark.circle.fill"
        case .view: return "eye.fill"
        case .deposit: return "arrow.down.circle.fill"
        case .withdraw: return "arrow.up.circle.fill"
        }
    }
}
