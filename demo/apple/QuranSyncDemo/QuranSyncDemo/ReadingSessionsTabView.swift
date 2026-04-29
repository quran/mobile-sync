import SwiftUI
import Shared

struct ReadingSessionsTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    var body: some View {
        NavigationView {
            Group {
                if viewModel.readingSessions.isEmpty {
                    VStack {
                        Spacer()
                        Text("No reading sessions yet.")
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                } else {
                    List {
                        ForEach(viewModel.readingSessions.indices, id: \.self) { index in
                            let readingSession = viewModel.readingSessions[index]
                            HStack {
                                Image(systemName: "clock")
                                    .foregroundColor(.blue)
                                VStack(alignment: .leading) {
                                    Text("Surah \(readingSession.chapterNumber), Ayah \(readingSession.verseNumber)")
                                        .font(.body)
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Reading Sessions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        Task {
                            let sura = Int32.random(in: 1...114)
                            let ayah = Int32.random(in: 1...7)
                            _ = await viewModel.addReadingSession(chapterNumber: sura, verseNumber: ayah)
                        }
                    }) {
                        Image(systemName: "plus")
                    }
                }
            }
        }
    }
}
