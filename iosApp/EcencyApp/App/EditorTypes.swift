import EcencyCore
import Foundation

/// The shared core's selection type (UTF-16 offsets). SwiftUI has a `TextSelection` of its own, and the module
/// name `EcencyCore` is shadowed by the core's `EcencyCore` object, so files that import SwiftUI use this name.
typealias CoreTextSelection = TextSelection
