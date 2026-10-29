import Combine
import EcencyCore
import Foundation

/// Mirrors one shared `StateModel` into SwiftUI. `watch` hands over the current state at once and
/// then every change on the main thread; the subscription lives exactly as long as this object.
@MainActor
final class Observed<S: AnyObject>: ObservableObject {
    @Published private(set) var state: S
    let model: StateModel<S>
    private var watcher: Closeable?
    /// Close the model too when this object goes away (screen-owned models, not env.session / env.votes).
    private let ownsModel: Bool

    init(_ model: StateModel<S>, ownsModel: Bool) {
        self.model = model
        self.ownsModel = ownsModel
        state = model.current
        watcher = model.watch { [weak self] s in
            MainActor.assumeIsolated { self?.state = s }
        }
    }

    deinit {
        watcher?.close()
        if ownsModel { model.close() }
    }
}
