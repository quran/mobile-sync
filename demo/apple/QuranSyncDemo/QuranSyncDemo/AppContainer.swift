import Shared

/**
 * App-level container around the shared dependency graph.
 *
 * It guarantees graph initialization before exposing any service,
 * so call sites never need to coordinate init order manually.
 */
final class AppContainer {
    static let shared = AppContainer()

    static var graph: AppGraph {
        shared.graph
    }

    let graph: AppGraph

    private init() {
        Shared.AuthFlowFactoryProvider.shared.doInitialize()

        let driverFactory = DriverFactory()

        self.graph = SharedDependencyGraph.shared.doInit(
            driverFactory: driverFactory,
            appEnvironment: AppEnvironment.prelive
        )
    }
}
