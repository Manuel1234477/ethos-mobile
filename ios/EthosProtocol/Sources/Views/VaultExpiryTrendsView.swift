import SwiftUI
import Charts

/// Issue #475: expiry trend analysis dashboard. See VaultExpiryAnalytics's
/// doc comment for what "lifespan" and "trend" mean here given the data this
/// client actually retains.
struct VaultExpiryTrendsView: View {
    @EnvironmentObject var vaultStore: VaultStore

    private var buckets: [VaultExpiryAnalytics.LifespanBucket] {
        VaultExpiryAnalytics.lifespanDistribution(for: vaultStore.vaults)
    }
    private var averageSeconds: Double {
        VaultExpiryAnalytics.averageLifespanSeconds(for: vaultStore.vaults)
    }
    private var upcoming: [VaultExpiryAnalytics.UpcomingExpiry] {
        VaultExpiryAnalytics.upcomingExpirations(for: vaultStore.vaults)
    }

    var body: some View {
        List {
            if vaultStore.vaults.isEmpty {
                ContentUnavailableView("No Vaults", systemImage: "chart.bar", description: Text("Create a vault to see expiry trends."))
            } else {
                Section("Check-In Interval Distribution") {
                    Chart(buckets) { bucket in
                        BarMark(
                            x: .value("Range", bucket.label),
                            y: .value("Vaults", bucket.count)
                        )
                    }
                    .frame(height: 180)
                }

                Section("Average Lifespan") {
                    LabeledContent("Mean Check-In Interval", value: formattedDuration(averageSeconds))
                }

                Section("Predicted Upcoming Deadlines") {
                    ForEach(upcoming) { entry in
                        HStack {
                            CopyableIDView(fullID: entry.vaultID, displayLength: 12)
                            Spacer()
                            Text(entry.predictedDate.formatted(date: .abbreviated, time: .omitted))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Expiry Trends")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func formattedDuration(_ seconds: Double) -> String {
        let days = seconds / 86_400
        if days >= 1 { return String(format: "%.1f days", days) }
        return String(format: "%.1f hours", seconds / 3_600)
    }
}
