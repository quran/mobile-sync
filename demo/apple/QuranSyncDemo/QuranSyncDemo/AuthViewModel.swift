import Foundation
import Combine
import AuthenticationServices
import Security
import CryptoKit
import Shared

/**
 * ViewModel for managing OAuth authentication on iOS.
 *
 * Implements OAuth 2.0 with PKCE (RFC 7636) for secure mobile authentication.
 * Handles:
 * - PKCE code generation (code_verifier and code_challenge)
 * - ASWebAuthenticationSession for secure OAuth consent screen
 * - OAuth redirect callback processing
 * - Token storage in iOS Keychain
 * - Token refresh and validation
 *
 * Uses Combine framework for reactive state management and SwiftUI integration.
 * Follows CLAUDE.md architecture by separating UI state (ObservableObject) from
 * business logic (AuthenticationManager).
 */
class AuthViewModel: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    @Published var authState: AuthState = .idle
    @Published var error: String?

    private let authManager = AuthenticationManager(usePreProduction: true)
    private var cancellables = Set<AnyCancellable>()

    override init() {
        super.init()
    }

    /**
     * Initiates OAuth login flow on iOS.
     *
     * Process:
     * 1. Generate PKCE code verifier (cryptographically random 128 characters)
     * 2. Create code challenge (SHA-256 hash + base64 encoding)
     * 3. Generate state parameter (CSRF protection)
     * 4. Store verifier and state for later validation
     * 5. Build authorization URL
     * 6. Launch ASWebAuthenticationSession for OAuth consent
     * 7. Handle redirect callback
     *
     * ASWebAuthenticationSession provides:
     * - Secure OAuth consent screen
     * - Session cookie sharing with Safari
     * - Automatic redirect handling
     */
    func login() {
        DispatchQueue.main.async {
            self.authState = .loading
            self.error = nil
        }

        do {
            // Generate PKCE code verifier (43-128 characters, unreserved characters)
            let codeVerifier = generateCodeVerifier()

            // Generate state for CSRF protection
            let state = generateRandomState()

            // Build authorization URL with PKCE
            let authUrl = authManager.buildAuthorizationUrl(
                codeVerifier: codeVerifier,
                state: state
            )

            guard let url = URL(string: authUrl) else {
                throw NSError(domain: "AuthViewModel", code: -1, userInfo: [
                    NSLocalizedDescriptionKey: "Invalid authorization URL"
                ])
            }

            // Store verifier and state for callback validation
            storeOAuthState(codeVerifier: codeVerifier, state: state)

            // Create ASWebAuthenticationSession for secure OAuth
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: "com.quran.oauth"
            ) { [weak self] callbackUrl, error in
                self?.handleAuthenticationCallback(url: callbackUrl, error: error)
            }

            // Set presentation context provider
            session.presentationContextProvider = self

            // Start the authentication session
            if session.start() {
                // Session started successfully
            } else {
                DispatchQueue.main.async {
                    self.error = "Failed to start authentication session"
                    self.authState = .error
                }
            }

        } catch {
            DispatchQueue.main.async {
                self.error = error.localizedDescription
                self.authState = .error
            }
        }
    }

    /**
     * Handles OAuth redirect callback from ASWebAuthenticationSession.
     *
     * Called when user completes OAuth authorization and is redirected back to app:
     * com.quran.oauth://callback?code=AUTH_CODE&state=STATE_VALUE
     *
     * Process:
     * 1. Parse redirect URL
     * 2. Check for error in redirect
     * 3. Validate state parameter (CSRF protection)
     * 4. Extract authorization code
     * 5. Exchange code for tokens using AuthenticationManager
     * 6. Store tokens in Keychain
     * 7. Update UI state
     */
    private func handleAuthenticationCallback(url: URL?, error: Error?) {
        DispatchQueue.main.async {
            self.authState = .loading
            self.error = nil
        }

        // Handle ASWebAuthenticationSession errors
        if let error = error {
            if let authError = error as? ASWebAuthenticationSessionError {
                if authError.code == .canceledLogin {
                    DispatchQueue.main.async {
                        self.authState = .idle
                        self.error = nil
                    }
                } else {
                    DispatchQueue.main.async {
                        self.error = "Authentication failed: \(authError.localizedDescription)"
                        self.authState = .error
                    }
                }
            } else {
                DispatchQueue.main.async {
                    self.error = error.localizedDescription
                    self.authState = .error
                }
            }
            return
        }

        guard let callbackUrl = url else {
            DispatchQueue.main.async {
                self.error = "No callback URL received"
                self.authState = .error
            }
            return
        }

        self.handleOAuthRedirect(url: callbackUrl)
    }

    /**
     * Handles OAuth redirect callback from deep link.
     *
     * Called when app is opened via deep link with authorization code.
     *
     * @param url The redirect URL containing authorization code or error
     */
    func handleOAuthRedirect(url: URL) {
        DispatchQueue.main.async {
            self.authState = .loading
            self.error = nil
        }

        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            DispatchQueue.main.async {
                self.error = "Invalid redirect URL"
                self.authState = .error
            }
            return
        }

        // Check for error parameter
        if let errorParam = components.queryItems?.first(where: { $0.name == "error" })?.value {
            let errorDescription = components.queryItems?
                .first(where: { $0.name == "error_description" })?.value ?? "Unknown error"
            DispatchQueue.main.async {
                self.error = "OAuth Error: \(errorParam) - \(errorDescription)"
                self.authState = .error
            }
            return
        }

        // Extract authorization code
        guard let authCode = components.queryItems?
            .first(where: { $0.name == "code" })?.value else {
            DispatchQueue.main.async {
                self.error = "No authorization code in redirect"
                self.authState = .error
            }
            return
        }

        // Extract and validate state parameter
        guard let returnedState = components.queryItems?
            .first(where: { $0.name == "state" })?.value else {
            DispatchQueue.main.async {
                self.error = "No state parameter in redirect"
                self.authState = .error
            }
            return
        }

        // Validate state (CSRF protection)
        let storedState = retrieveStoredState()
        guard storedState == returnedState else {
            DispatchQueue.main.async {
                self.error = "State parameter mismatch - possible CSRF attack"
                self.authState = .error
            }
            return
        }

        // Retrieve stored code verifier
        guard let codeVerifier = retrieveStoredCodeVerifier() else {
            DispatchQueue.main.async {
                self.error = "Code verifier not found - invalid state"
                self.authState = .error
            }
            return
        }

        // Exchange code for tokens on background thread
        Task {
            do {
                // Exchange authorization code for tokens
                let tokenResponse = try await self.authManager.exchangeCodeForToken(
                    code: authCode,
                    codeVerifier: codeVerifier
                )

                // Store tokens in Keychain
                try self.storeTokensInKeychain(tokenResponse)
                
                let runner = await MainActor.run {
                    // Clear stored OAuth state
                    self.clearOAuthState()
                    self.authState = .success
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    self.authState = .error
                }
            }
        }
    }

    /**
     * Refreshes the access token if expired.
     *
     * Should be called before making API requests to ensure valid token.
     */
    func refreshAccessTokenIfNeeded() async -> Bool {
        do {
            guard let refreshToken = try retrieveTokenFromKeychain(key: "refreshToken") else {
                return false
            }

            let expirationTime = UserDefaults.standard.double(forKey: "tokenExpirationTime")

            // Check if token is expired (with 60 second buffer)
            if !authManager.isTokenValid(expirationTime: Int64(expirationTime)) {
                let newTokenResponse = try await authManager.refreshToken(refreshToken: refreshToken)
                try storeTokensInKeychain(newTokenResponse)
                return true
            } else {
                return true
            }
        } catch {
            return false
        }
    }

    /**
     * Logs out user by revoking tokens and clearing storage.
     */
    func logout() {
        Task {
            do {
                if let accessToken = try self.retrieveTokenFromKeychain(key: "accessToken") {
                    _ = try await self.authManager.revokeToken(token: accessToken, tokenTypeHint: "access_token")
                }

                if let refreshToken = try self.retrieveTokenFromKeychain(key: "refreshToken") {
                    _ = try await self.authManager.revokeToken(token: refreshToken, tokenTypeHint: "refresh_token")
                }

                await MainActor.run {
                    self.clearAllTokens()
                    self.authState = .idle
                    self.error = nil
                }
            } catch {
                await MainActor.run {
                    self.error = "Logout failed: \(error.localizedDescription)"
                }
            }
        }
    }

    /**
     * Retrieves stored access token.
     */
    func getAccessToken() -> String? {
        try? retrieveTokenFromKeychain(key: "accessToken")
    }

    func clearError() {
        error = nil
    }

    // ========================= Helper Methods =========================

    /**
     * Generates a cryptographically random PKCE code verifier.
     *
     * RFC 7636: 43-128 characters from unreserved characters
     * [A-Z] [a-z] [0-9] - . _ ~
     */
    private func generateCodeVerifier() -> String {
        let charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        var result = ""
        for _ in 0..<128 {
            let randomIndex = Int.random(in: 0..<charset.count)
            let index = charset.index(charset.startIndex, offsetBy: randomIndex)
            result.append(charset[index])
        }
        return result
    }

    /**
     * Generates a random state parameter for CSRF protection.
     */
    private func generateRandomState() -> String {
        let charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        var result = ""
        for _ in 0..<32 {
            let randomIndex = Int.random(in: 0..<charset.count)
            let index = charset.index(charset.startIndex, offsetBy: randomIndex)
            result.append(charset[index])
        }
        return result
    }

    /**
     * Stores OAuth state (code_verifier, state) in UserDefaults for callback validation.
     */
    private func storeOAuthState(codeVerifier: String, state: String) {
        UserDefaults.standard.set(codeVerifier, forKey: "oauth_code_verifier")
        UserDefaults.standard.set(state, forKey: "oauth_state")
    }

    /**
     * Clears OAuth state after successful authentication.
     */
    private func clearOAuthState() {
        UserDefaults.standard.removeObject(forKey: "oauth_code_verifier")
        UserDefaults.standard.removeObject(forKey: "oauth_state")
    }

    /**
     * Retrieves stored code verifier for token exchange.
     */
    private func retrieveStoredCodeVerifier() -> String? {
        UserDefaults.standard.string(forKey: "oauth_code_verifier")
    }

    /**
     * Retrieves stored state parameter for validation.
     */
    private func retrieveStoredState() -> String? {
        UserDefaults.standard.string(forKey: "oauth_state")
    }

    /**
     * Stores tokens securely in iOS Keychain.
     *
     * Keychain provides encrypted storage for sensitive data.
     */
    private func storeTokensInKeychain(_ tokenResponse: Shared.TokenResponse) throws {
        let expirationTime = Date().timeIntervalSince1970 + Double(tokenResponse.expiresIn)

        // Store access token
        try storeTokenInKeychain(token: tokenResponse.accessToken, key: "accessToken")

        // Store refresh token if provided
        if let refreshToken = tokenResponse.refreshToken {
            try storeTokenInKeychain(token: refreshToken, key: "refreshToken")
        }

        // Store expiration time in UserDefaults
        UserDefaults.standard.set(expirationTime, forKey: "tokenExpirationTime")
    }

    /**
     * Stores a single token in Keychain.
     */
    private func storeTokenInKeychain(token: String, key: String) throws {
        let data = token.data(using: .utf8)!

        // First, try to delete existing value
        var deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        // Add new value
        var addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: "Keychain", code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: "Failed to store token in Keychain"
            ])
        }
    }

    /**
     * Retrieves a token from Keychain.
     */
    private func retrieveTokenFromKeychain(key: String) throws -> String? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else {
            if status == errSecItemNotFound {
                return nil
            }
            throw NSError(domain: "Keychain", code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: "Failed to retrieve token from Keychain"
            ])
        }

        guard let data = result as? Data else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    /**
     * Clears all stored tokens from Keychain and UserDefaults.
     */
    private func clearAllTokens() {
        let keys = ["accessToken", "refreshToken", "oauth_code_verifier", "oauth_state", "tokenExpirationTime"]

        for key in keys {
            var query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: key
            ]
            SecItemDelete(query as CFDictionary)
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    // MARK: - ASWebAuthenticationPresentationContextProviding

    /**
     * Provides the presentation anchor for ASWebAuthenticationSession.
     *
     * Required by ASWebAuthenticationPresentationContextProviding protocol.
     */
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return ASPresentationAnchor()
    }
}

/**
 * Represents the authentication state machine.
 */
enum AuthState {
    case idle
    case loading
    case success
    case error
}

// MARK: - Helper Functions

/**
 * Generates a cryptographically random PKCE code verifier.
 */
private func generateCodeVerifier() -> String {
    let charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    var result = ""
    for _ in 0..<128 {
        let randomIndex = Int.random(in: 0..<charset.count)
        let index = charset.index(charset.startIndex, offsetBy: randomIndex)
        result.append(charset[index])
    }
    return result
}

/**
 * Generates a random state parameter for CSRF protection.
 */
private func generateRandomState() -> String {
    let charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    var result = ""
    for _ in 0..<32 {
        let randomIndex = Int.random(in: 0..<charset.count)
        let index = charset.index(charset.startIndex, offsetBy: randomIndex)
        result.append(charset[index])
    }
    return result
}


