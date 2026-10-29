import EcencyCore
import SwiftUI

/// The header above a list that closes on scroll down and opens near the top or on a drag up (RN profileView, the
/// shared `SummaryCollapse`), fed by `collapsesSummary`. Waits out the rule's cooldown with a task, as RN's recheck
/// timer.
@MainActor
final class SummaryCollapseDriver: ObservableObject {
    @Published private(set) var open = true
    private let rule = SummaryCollapse()
    private var recheck: Task<Void, Never>?

    private static func now() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    /// The list moved: by the reader ([byReader]), or by layout (a clamp after the header closed or the viewport
    /// changed, a programmatic scroll), which never counts as a drag: a closed header over a list that cannot
    /// [scrollable] scroll is held closed, a scrollable list moved by code to the top opens it.
    func scrolled(to offset: CGFloat, byReader: Bool = true, scrollable: Bool = true) {
        let at = Self.now()
        if byReader {
            settle(rule.onScroll(offset: Double(offset), nowMillis: at), at)
        } else {
            settle(rule.layout(offset: Double(offset), scrollable: scrollable, nowMillis: at), at)
        }
    }

    /// Another tab's list under the header: open over its top.
    func reset() {
        let at = Self.now()
        rule.reset(nowMillis: at)
        settle(nil, at)
    }

    /// A tap on the compact header (RN CollapsibleCard handleOnExpanded).
    func expand() {
        let at = Self.now()
        if rule.expand(nowMillis: at) { settle(nil, at) }
    }

    /// A tap on the title of an open card (community Details); it stays closed at the top.
    func collapse() {
        let at = Self.now()
        if rule.collapse(nowMillis: at) { settle(nil, at) }
    }

    func toggle() { open ? collapse() : expand() }

    private var recheckDue: Int64?

    private func settle(_ wait: KotlinLong?, _ at: Int64) {
        if open != rule.isOpen { open = rule.isOpen }
        guard let wait = wait?.int64Value else {
            recheck?.cancel()
            recheck = nil
            recheckDue = nil
            return
        }
        let due = at + wait
        // every scroll event inside the cooldown asks for the same moment: keep the waiting task
        if due == recheckDue, recheck != nil { return }
        recheck?.cancel()
        recheckDue = due
        recheck = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(wait, 0)) * 1_000_000)
            guard !Task.isCancelled, let self else { return }
            self.recheck = nil
            self.recheckDue = nil
            self.settle(self.rule.recheck(nowMillis: due), due)
        }
    }

    deinit { recheck?.cancel() }
}

extension View {
    /// Hands this ScrollView's movement to [collapse]; nothing is added without one. Its stack calls
    /// `reportsScrollOffset(space)`. Only the reader's scrolling counts (`CollapsesSummary.isReader`): iOS 18 reads the
    /// scroll phase and geometry, iOS 17 the offset and content height preferences in its own [space] (it works
    /// without `scrollTopPopup`).
    @ViewBuilder func collapsesSummary(_ collapse: SummaryCollapseDriver?, space: String) -> some View {
        if let collapse {
            modifier(CollapsesSummary(collapse: collapse, space: space))
        } else {
            self
        }
    }
}

struct ScrollMetrics: Equatable {
    var offset: CGFloat
    var scrollable: Bool
    /// The visible height of the list (it changes when the header above closes or opens).
    var viewport: CGFloat
}

struct CollapsesSummary: ViewModifier {
    let collapse: SummaryCollapseDriver
    let space: String
    @State private var userScrolling = false
    @State private var pending = Pending()

    /**
     The reader moved it: a drag or fling (not a programmatic `.animating` scroll, not idle layout) of a list that can
     scroll, or a pull past its top, and never together with a change of the visible height (a clamp when the list
     grew is layout, wherever it lands).
     */
    static func isReader(_ m: ScrollMetrics, scrolling: Bool, resized: Bool) -> Bool {
        !resized && scrolling && (m.scrollable || m.offset < 0)
    }

    /// iOS 17: offset, content height and viewport arrive separately; they are paired once per run loop turn, so their
    /// order does not matter.
    final class Pending {
        var offset: CGFloat?
        var content: CGFloat = 0
        var viewport: CGFloat = 0
        var flushedViewport: CGFloat?
        var scheduled = false
    }

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .onScrollPhaseChange { _, phase in userScrolling = phase == .interacting || phase == .decelerating }
                .onScrollGeometryChange(for: ScrollMetrics.self, of: {
                    ScrollMetrics(offset: $0.contentOffset.y + $0.contentInsets.top,
                                  scrollable: $0.contentSize.height > $0.containerSize.height - $0.contentInsets.top - $0.contentInsets.bottom + 0.5,
                                  viewport: $0.containerSize.height)
                }) { old, m in
                    deliver(m, scrolling: userScrolling, resized: abs(old.viewport - m.viewport) > 0.5)
                }
        } else {
            content
                .coordinateSpace(name: space)
                .background(GeometryReader { g in
                    Color.clear.onAppear { pending.viewport = g.size.height }.onChange(of: g.size.height) { _, h in
                        pending.viewport = h
                        schedule()
                    }
                })
                .onPreferenceChange(ScrollContentHeightKey.self) { h in
                    MainActor.assumeIsolated { if let h { pending.content = h } }
                }
                .onPreferenceChange(ScrollOffsetKey.self) { offset in
                    MainActor.assumeIsolated {
                        guard let offset else { return }
                        pending.offset = offset
                        schedule()
                    }
                }
        }
    }

    private func deliver(_ m: ScrollMetrics, scrolling: Bool, resized: Bool) {
        let reader = Self.isReader(m, scrolling: scrolling, resized: resized)
        // a clamp from a size change never opens at the top: only code that moves a list of stable size does
        collapse.scrolled(to: m.offset, byReader: reader, scrollable: m.scrollable && !resized)
    }

    /// iOS 17 (no scroll phase): what moves a list that can scroll is taken as the reader, unless the viewport changed
    /// in the same turn.
    @MainActor private func schedule() {
        guard !pending.scheduled else { return }
        pending.scheduled = true
        let box = pending
        DispatchQueue.main.async {
            box.scheduled = false
            let resized = box.flushedViewport.map { abs($0 - box.viewport) > 0.5 } ?? false
            box.flushedViewport = box.viewport
            guard let offset = box.offset else { return }
            box.offset = nil
            deliver(ScrollMetrics(offset: offset, scrollable: box.content > box.viewport + 0.5, viewport: box.viewport), scrolling: true, resized: resized)
        }
    }
}

/// A header that is never taller than [maxHeight]: past that (large text, small screens) it scrolls inside, so the
/// list below keeps room to be scrolled and close it.
struct CappedHeader<Content: View>: View {
    let maxHeight: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        // exactly as tall as what it shows: a `.frame(maxHeight:)` here took any extra height its stack offered and
        // centred the summary in it, a gap above the cover and below the summary (owner note 10-29)
        HugTopLayout(maxHeight: maxHeight) {
            ViewThatFits(in: .vertical) {
                content()
                ScrollView { content() }.frame(height: maxHeight).scrollBounceBehavior(.basedOnSize)
            }
        }
    }
}

/// One child, always offered [maxHeight] and sized to the height it takes (never more), placed at the top. The size
/// does not depend on what the parent offers, so a stack can neither stretch it (a gap) nor squeeze it (which made
/// the header pick its scrolling form and overlap the views below).
struct HugTopLayout: Layout {
    let maxHeight: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let size = child.sizeThatFits(ProposedViewSize(width: proposal.width, height: maxHeight))
        return CGSize(width: proposal.width ?? size.width, height: min(size.height, maxHeight))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: maxHeight))
    }
}

/// The size rule of the profile summary and the community card: at accessibility text sizes (or a compact height,
/// which the portrait-only iPhone app never has) the open header would leave the list no room, so it starts closed,
/// closes when the size changes into the rule and stays closed on a tab switch ([resetForSize]).
enum SummarySizeRule {
    static func holds(_ typeSize: DynamicTypeSize, _ verticalSizeClass: UserInterfaceSizeClass?) -> Bool {
        typeSize >= .stacksRows || verticalSizeClass == .compact
    }
}

extension SummaryCollapseDriver {
    /// A tab switch: open over the new list, unless the size rule holds.
    func resetForSize(_ holds: Bool) {
        reset()
        if holds { collapse() }
    }
}

extension View {
    /// Applies [SummarySizeRule] to [collapse]: closed on appear (once: back from a pushed screen keeps the reader's
    /// choice) and whenever the text size or the height class moves into the rule; open again when it moves back out,
    /// if the rule was what closed it (a text size tried and undone, as Xcode's accessibility audit does).
    func closesSummaryForSize(_ collapse: SummaryCollapseDriver) -> some View { modifier(SummarySizeModifier(collapse: collapse)) }
}

private struct SummarySizeModifier: ViewModifier {
    @ObservedObject var collapse: SummaryCollapseDriver
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var applied = false
    /// The rule closed the header and the reader has not opened it since.
    @State private var closedByRule = false

    func body(content: Content) -> some View {
        let holds = SummarySizeRule.holds(typeSize, verticalSizeClass)
        content
            .onAppear {
                if !applied, holds {
                    applied = true
                    close()
                }
            }
            .onChange(of: holds) { _, now in
                if now {
                    close()
                } else if closedByRule {
                    closedByRule = false
                    if !collapse.open { collapse.expand() }
                }
            }
            .onChange(of: collapse.open) { _, open in if open { closedByRule = false } }
    }

    private func close() {
        guard collapse.open else { return }
        collapse.collapse()
        closedByRule = true
    }
}
