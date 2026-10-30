#if DEBUG
import EcencyCore
import SwiftUI

/// Debug only (`-iconGallery YES`): every AppIcon, outlined and filled, in the app's colours, to check the icon set
/// by eye (weight, alignment, clipping, RTL mirroring, Dynamic Type). Compiled out of release builds.
struct IconGalleryView: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView {
            Text("Icons (\(AppIcon.entries.count))").font(.headline).foregroundStyle(theme.text)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], spacing: 12) {
                ForEach(AppIcon.entries, id: \.self) { icon in
                    VStack(spacing: 4) {
                        HStack(spacing: 8) {
                            glyph(icon, filled: false).foregroundStyle(theme.text)
                            glyph(icon, filled: true).foregroundStyle(theme.accent)
                        }
                        Text(icon.name.lowercased()).font(.caption2).foregroundStyle(theme.secondary)
                            .multilineTextAlignment(.center).lineLimit(2)
                    }
                }
            }
            .padding(8)
        }
        .background(theme.background)
        .accessibilityIdentifier("icon-gallery")
    }

    @ViewBuilder private func glyph(_ icon: AppIcon, filled: Bool) -> some View {
        if icon == .waves {
            WavyDash().frame(width: 24, height: 24)
        } else {
            Image(appIcon: icon, filled: filled).font(.title2)
        }
    }
}
#endif
