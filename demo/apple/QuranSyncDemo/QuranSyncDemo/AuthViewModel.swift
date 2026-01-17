import Foundation
import Combine
import AuthenticationServices
import Shared

/**
 * A thin Swift wrapper for the shared Kotlin AuthViewModel.
 *
 * This wrapper:
 * 1. Conforms to ObservableObject for SwiftUI integration.
 * 2. Bridges Kotlin StateFlows to SwiftUI @Published properties.
 * 3. Provides iOS-specific ASWebAuthenticationSession handling.
 */
class AuthViewModel: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    // The shared Kotlin ViewModel
    private let commonViewModel: Shared.AuthViewModel
    
    // Bridged state for SwiftUI
    @Published var authState: Shared.AuthState = Shared.AuthState.Idle()
    @Published var error: String? = nil
    
    private var cancellables = Set<AnyCancellable>()
    private var authStateWatcher: Shared.FlowWatcher? = nil
    private var errorWatcher: Shared.FlowWatcher? = nil

    override init() {
        self.commonViewModel = Shared.AuthViewModel()
        super.init()
        
        // Start watching Kotlin flows
        observeState()
    }
    
    private func observeState() {
        // Watch authState flow
        authStateWatcher = commonViewModel.authState.watch { [weak self] state in
            guard let self = self else { return }
            self.authState = state
            
            // If the state informs us to start the auth flow, we do it here
            if let startAuth = state as? Shared.AuthState.StartAuthFlow {
                self.launchAuthSession(url: startAuth.authUrl)
            }
        }
        
        // Watch error flow
        errorWatcher = commonViewModel.error.watch { [weak self] error in
            self?.error = error
        }
    }

    func login() {
        commonViewModel.login()
    }

    func logout() {
        commonViewModel.logout()
    }
    
    func clearError() {
        commonViewModel.clearError()
    }

    /**
     * Launch iOS-specific authentication session.
     */
    private func launchAuthSession(url: String) {
        guard let authUrl = URL(string: url) else {
            self.error = "Invalid authorization URL"
            return
        }

        let session = ASWebAuthenticationSession(
            url: authUrl,
            callbackURLScheme: "com.quran.oauth"
        ) { [weak self] callbackUrl, error in
            if let error = error {
                if let authError = error as? ASWebAuthenticationSessionError, authError.code == .canceledLogin {
                    self?.commonViewModel.clearError() // Just reset
                } else {
                    // Fail through to the ViewModel
                    self?.error = error.localizedDescription
                }
                return
            }

            if let callbackUrl = callbackUrl {
                self?.commonViewModel.handleOAuthRedirect(redirectUri: callbackUrl.absoluteString)
            }
        }

        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        session.start()
    }

    // MARK: - ASWebAuthenticationPresentationContextProviding
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return ASPresentationAnchor()
    }
    
    deinit {
        authStateWatcher?.cancel()
        errorWatcher?.cancel()
    }
}
