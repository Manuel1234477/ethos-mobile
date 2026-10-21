import SwiftUI
import VisionKit

/// Issue #472: scans a vault sharing QR code and feeds the decoded URL through
/// the exact same `UniversalLinkRouter.parse` pipeline a tapped universal link
/// already goes through, so import reuses the existing DeepLinkView handling
/// for `.vaultInvitation` rather than a second, parallel import path.
struct VaultQRScannerView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRScannerRepresentable { payload in
                        handleScannedPayload(payload)
                    }
                } else {
                    ContentUnavailableView(
                        "Scanning Unavailable",
                        systemImage: "qrcode.viewfinder",
                        description: Text("This device doesn't support QR scanning.")
                    )
                }
            }
            .navigationTitle("Scan Vault QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert("Invalid QR Code", isPresented: .constant(errorMessage != nil), actions: {
                Button("OK") { errorMessage = nil }
            }, message: {
                Text(errorMessage ?? "")
            })
        }
    }

    private func handleScannedPayload(_ payload: String) {
        guard let url = URL(string: payload),
              let link = UniversalLinkRouter.shared.parse(url: url) else {
            errorMessage = "This QR code isn't a recognized vault sharing link."
            return
        }
        vaultStore.pendingDeepLink = link
        dismiss()
    }
}

/// `UIViewControllerRepresentable` wrapper around VisionKit's
/// `DataScannerViewController`, configured to recognize QR codes only.
private struct QRScannerRepresentable: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                    onScan(payload)
                    return
                }
            }
        }
    }
}
