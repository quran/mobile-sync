import SwiftUI
import Shared

struct BookmarksTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    let readingBookmark: Shared.ReadingBookmark?
    
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        List {
            Section(header: Text("Current Reading Bookmark")) {
                if let readingBookmark = readingBookmark {
                    HStack {
                        Image(systemName: "bookmark.fill")
                            .foregroundColor(.orange)
                        VStack(alignment: .leading) {
                            Text("Surah \(readingBookmark.sura), Ayah \(readingBookmark.ayah)")
                                .font(.body)
                        }
                        Spacer()
                        Button(action: {
                            Task {
                                await viewModel.deleteReadingBookmark()
                            }
                        }) {
                            Image(systemName: "trash")
                                .foregroundColor(.red)
                        }
                        .buttonStyle(BorderlessButtonStyle())
                    }
                } else {
                    Text("No reading bookmark set.")
                        .foregroundColor(.secondary)
                        .italic()
                }
            }

            Section(header: HStack {
                Text("Your Bookmarks")
                Spacer()
                HStack(spacing: 16) {
                    Button(action: {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        Task {
                            _ = await viewModel.addBookmark(sura: sura, ayah: ayah)
                        }
                    }) {
                        Image(systemName: "plus.square")
                    }
                    Button(action: {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        Task {
                            _ = await viewModel.addReadingBookmark(sura: sura, ayah: ayah)
                        }
                    }) {
                        Image(systemName: "bookmark")
                            .foregroundColor(.orange)
                    }
                }
            }) {
                if viewModel.bookmarks.isEmpty {
                    Text("No bookmarks yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.bookmarks, id: \.localId) { bookmark in
                        HStack {
                            Image(systemName: "bookmark.fill")
                                .foregroundColor(.accentColor)
                            
                            VStack(alignment: .leading) {
                                Text("Surah \(bookmark.sura), Ayah \(bookmark.ayah)")
                                    .font(.body)

                                Text("\(dateFormatter.string(from: bookmark.lastUpdated))")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                            
                            Spacer()
                            
                            Button(action: {
                                Task {
                                    await viewModel.deleteBookmark(bookmark: bookmark)
                                }
                            }) {
                                Image(systemName: "trash")
                                    .foregroundColor(.red)
                            }
                            .buttonStyle(BorderlessButtonStyle())
                        }
                    }
                }
            }
        }
    }
}
