import EcencyCore
import SwiftUI

/// Debug-only renderer demo (launch with `-rendererDemo YES`): the synthetic sample post through the real pipeline.
struct RendererDemoView: View {
    @Environment(\.colorScheme) private var colorScheme
    // The demo post goes through the real pipeline: render-helper HTML in, native blocks out.
    private let blocks = BlockBuilder.shared.build(html: SampleHtml.shared.POST)

    // `simctl launch <udid> app.esteem.mobile.ios -showImages NO` previews the "Show images" off mode.
    private let showImages = UserDefaults.standard.object(forKey: "showImages") == nil || UserDefaults.standard.bool(forKey: "showImages")
    // `-skipBlocks N` starts the preview N blocks in, for screenshots of the lower blocks.
    private let skipBlocks = UserDefaults.standard.integer(forKey: "skipBlocks")
    @State private var toast: String?

    // Stand-ins for the copy sheet and the gallery until those screens exist.
    private var actions: PostActions {
        PostActions(
            onImage: { url, all in show(UiCopy.shared.imageOf(index: Int32((all.firstIndex(of: url) ?? 0) + 1), count: Int32(all.count))) },
            onParagraphLongPress: { show($0) },
            showImages: showImages
        )
    }

    private func show(_ text: String) {
        toast = text
        // UI tests hold the toast (`-demoToastSeconds 60`): on a loaded simulator a 1 s long press returns to the
        // test seconds after the gesture fired, by which time a 2 s toast is gone
        let seconds = UserDefaults.standard.double(forKey: "demoToastSeconds")
        Task {
            try? await Task.sleep(for: .seconds(seconds > 0 ? seconds : 2))
            if toast == text { toast = nil }
        }
    }

    private var theme: PostTheme { colorScheme == .dark ? .dark : .light }

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(EcencyCore.shared.greeting())
                        .padding(.vertical, 8)
                        .accessibilityIdentifier("greeting")
                    // 16pt gutters, as the RN post screen (content width = window - 32).
                    PostBodyView(blocks: Array((blocks + RendererSamples.shared.extras).dropFirst(skipBlocks)), contentWidth: geo.size.width - 32, actions: actions)
                }
                .padding(.horizontal, 16)
            }
            .accessibilityIdentifier("post")
        }
        .speakPlayersScope()
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .scaledFont(14, relativeTo: .subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 8))
                    .padding(.bottom, 40)
                    .accessibilityIdentifier("toast")
            }
        }
        .background(theme.background)
        .foregroundStyle(theme.text)
        .environment(\.postTheme, theme)
    }
}

#Preview {
    RendererDemoView()
}
