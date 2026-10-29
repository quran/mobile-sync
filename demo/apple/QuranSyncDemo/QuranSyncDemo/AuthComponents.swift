import SwiftUI
import Shared

struct LoginButtonContent: View {
    let onLogin: () async -> Void

    var body: some View {
        VStack(spacing: 16) {
            Button(action: {
                Task {
                    await onLogin()
                }
            }) {
                Text("Sign in with OAuth")
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.accentColor)
                    .cornerRadius(8)
            }

            Text("You will be redirected to Quran Foundation to securely sign in.")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }
}

struct LoadingContent: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5, anchor: .center)
                .padding()

            Text("Signing in...")
                .font(.body)
        }
    }
}

struct ErrorContent: View {
    let message: String
    let onDismiss: () -> Void
    let onRetry: () async -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 60))
                .foregroundColor(.red)

            Text("Authentication Failed")
                .font(.headline)

            Text(message)
                .font(.caption)
                .foregroundColor(.red)
                .padding(12)
                .background(Color(.systemRed).opacity(0.1))
                .cornerRadius(8)
                .multilineTextAlignment(.center)

            HStack(spacing: 12) {
                Button("Dismiss") {
                    onDismiss()
                }
                .buttonStyle(.bordered)

                Button("Retry") {
                    Task {
                        await onRetry()
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}
