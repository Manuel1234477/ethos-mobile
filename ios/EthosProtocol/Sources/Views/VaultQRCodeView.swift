import SwiftUI
import CoreImage.CIFilterBuiltins

/// Issue #472: generates and displays a QR code encoding a vault's existing
/// universal invitation link (`https://ethos-protocol.app/vaults/{id}/invite`
/// — the exact same link UniversalLinkRouter already parses for
/// `DeepLink.vaultInvitation`), so sharing a vault doesn't require manually
/// typing or copy-pasting the link or vault ID.
struct VaultQRCodeView: View {
    let vault: Vault

    private var invitationURL: URL {
        URL(string: "https://ethos-protocol.app/vaults/\(vault.id)/invite")!
    }

    private var qrImage: UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(invitationURL.absoluteString.utf8)
        filter.correctionLevel = "M"
        guard let outputImage = filter.outputImage else { return nil }
        // The raw CIImage is tiny (a handful of pixels per QR module) — scale
        // up with nearest-neighbor so it renders crisply, not blurry.
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    var body: some View {
        VStack(spacing: 24) {
            Text("Share Vault")
                .font(.title2.bold())

            if let qrImage {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 240, height: 240)
                    .padding()
                    .background(Color.white)
                    .cornerRadius(16)
                    .shadow(radius: 4)
            } else {
                ContentUnavailableView("Couldn't Generate QR Code", systemImage: "qrcode")
            }

            CopyableIDView(fullID: vault.id, displayLength: 16)

            ShareLink(item: invitationURL) {
                Label("Share Link", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.bordered)

            Spacer()
        }
        .padding()
        .navigationTitle("Vault QR Code")
        .navigationBarTitleDisplayMode(.inline)
    }
}
