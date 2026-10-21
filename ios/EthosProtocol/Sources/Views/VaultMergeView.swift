import SwiftUI

/// Issue #474: lets the user review detected duplicate-vault groups (same
/// beneficiary) and choose which vault to keep visible, hiding the other(s).
/// See VaultMergeStore's doc comment for why this is a local hide/merge
/// rather than a real server-side merge — vault deletion isn't implemented
/// server-side at all, so there's no API to actually combine or remove a
/// vault's on-chain state.
struct VaultMergeView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @State private var selectedKeeper: [String: String] = [:] // group.id -> chosen vault id

    var body: some View {
        List {
            if vaultStore.duplicateGroups.isEmpty {
                ContentUnavailableView("No Duplicates Found", systemImage: "checkmark.circle",
                                        description: Text("No vaults share the same beneficiary address."))
            }
            ForEach(vaultStore.duplicateGroups) { group in
                Section {
                    ForEach(group.vaults) { vault in
                        Button {
                            selectedKeeper[group.id] = vault.id
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    CopyableIDView(fullID: vault.id, displayLength: 12)
                                    Text(vault.formattedBalance)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    // #474: both vaults' check-in dates stay visible here,
                                    // even after merging, instead of being discarded.
                                    Text("Last check-in: \(vault.lastCheckIn.formatted(date: .abbreviated, time: .omitted))")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if selectedKeeper[group.id] == vault.id {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }

                    Button {
                        mergeGroup(group)
                    } label: {
                        Label("Merge — Hide the Other Vault", systemImage: "arrow.triangle.merge")
                    }
                    .disabled(selectedKeeper[group.id] == nil)
                } header: {
                    Text("Shared beneficiary")
                } footer: {
                    Text("Choose which vault to keep visible. The other stays accessible and can be un-merged later from its detail screen — this doesn't delete or combine any on-chain balance.")
                        .font(.caption2)
                }
            }
        }
        .navigationTitle("Duplicate Vaults")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func mergeGroup(_ group: VaultDuplicateGroup) {
        guard let keeperId = selectedKeeper[group.id] else { return }
        for vault in group.vaults where vault.id != keeperId {
            VaultMergeStore.merge(vault.id, into: keeperId)
        }
        vaultStore.vaults = vaultStore.vaults // trigger @Published re-evaluation
    }
}
