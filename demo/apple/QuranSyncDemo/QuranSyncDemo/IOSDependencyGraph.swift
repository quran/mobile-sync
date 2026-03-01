import Shared

/**
 * iOS facade around the shared dependency graph.
 *
 * It guarantees graph initialization before exposing any service,
 * so call sites never need to coordinate init order manually.
 */
final class IOSDependencyGraph {
    static let shared = IOSDependencyGraph()

    private let driverFactory = DriverFactory()
    private let environment = SynchronizationEnvironment(
        endPointURL: "https://apis-prelive.quran.foundation/auth"
    )

    private init() {
        Shared.AuthFlowFactoryProvider.shared.doInitialize()
    }

    private lazy var graph: AppGraph = {
        SharedDependencyGraph.shared.doInit(
            driverFactory: driverFactory,
            environment: environment
        )
    }()

    func get() -> AppGraph {
        graph
    }
}
