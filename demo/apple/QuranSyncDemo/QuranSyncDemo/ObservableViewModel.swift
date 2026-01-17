import Foundation
import Shared
import Combine

/**
 * A generic wrapper that makes any Kotlin ViewModel observable in SwiftUI.
 *
 * Usage:
 * @StateObject var viewModel = ObservableViewModel(MyKotlinViewModel()) { vm, object in
 *     // Register flows to trigger UI updates
 *     vm.someStateFlow.watch { _ in object.objectWillChange.send() }
 * }
 */
@MainActor
class ObservableViewModel<VM: Shared.AuthViewModel>: ObservableObject {
    let kt: VM
    private var watchers: [Shared.FlowWatcher] = []
    
    init(_ viewModel: VM, setup: (VM, ObservableViewModel<VM>) -> [Shared.FlowWatcher]) {
        self.kt = viewModel
        self.watchers = setup(viewModel, self)
    }
    
    deinit {
        watchers.forEach { $0.cancel() }
        watchers.removeAll()
    }
}
