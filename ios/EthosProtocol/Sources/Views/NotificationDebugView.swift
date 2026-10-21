import SwiftUI

#if DEBUG
/// Debug-only screen listing the local notification delivery log (#235), for
/// QA/support to answer "did this notification actually get scheduled,
/// delivered, or suppressed?" without backend log correlation. Never shows
/// anything beyond vault ID / event type / timestamp — see
/// NotificationDeliveryLog's doc comment for what is deliberately excluded.
struct NotificationDebugView: View {
    @State private var events: [NotificationDeliveryEvent] = []
    @State private var stats: [NotificationDeliveryLog.EngagementStats] = []

    var body: some View {
        List {
            // Issue #473: engagement analytics dashboard — tap rate and
            // completion rate per notification type, from the same bounded
            // local log the event list below already reads.
            if !stats.isEmpty {
                Section("Engagement") {
                    ForEach(stats, id: \.eventType) { stat in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(stat.eventType)
                                .font(.subheadline.bold())
                            HStack {
                                Text("Delivered: \(stat.delivered)")
                                Spacer()
                                Text("Tapped: \(stat.tapped)")
                                Spacer()
                                Text("Dismissed: \(stat.dismissed)")
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            HStack {
                                Text("Tap rate: \(Int(stat.tapRate * 100))%")
                                Spacer()
                                Text("Completion rate: \(Int(stat.completionRate * 100))%")
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            if events.isEmpty {
                Text("No notification events logged yet.")
                    .foregroundStyle(.secondary)
            }
            ForEach(events) { event in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(event.kind.rawValue.capitalized)
                            .font(.subheadline.bold())
                            .foregroundStyle(color(for: event.kind))
                        Spacer()
                        Text(event.source.rawValue)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(event.eventType)
                        .font(.subheadline)
                    HStack {
                        CopyableIDView(fullID: event.vaultID, displayLength: 12)
                        Spacer()
                        Text(event.timestamp, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .navigationTitle("Notification Log")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Clear") {
                    NotificationDeliveryLog.shared.clear()
                    events = []
                    stats = []
                }
            }
        }
        .task { reload() }
        .refreshable { reload() }
    }

    private func reload() {
        events = NotificationDeliveryLog.shared.recentEvents()
        stats = NotificationDeliveryLog.shared.engagementStats()
    }

    private func color(for kind: NotificationDeliveryEvent.Kind) -> Color {
        switch kind {
        case .scheduled: return .blue
        case .delivered: return .green
        case .suppressed: return .orange
        case .tapped: return .purple
        case .dismissed: return .gray
        case .completed: return .mint
        }
    }
}
#endif
