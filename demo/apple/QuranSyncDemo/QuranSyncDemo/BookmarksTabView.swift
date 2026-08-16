import SwiftUI
import Shared

struct BookmarksTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    let readingBookmark: Shared.ReadingBookmark?
    
    var body: some View {
        List {
            Section(header: Text("Current Reading Bookmark")) {
                if let readingBookmark = readingBookmark {
                    HStack {
                        Image(systemName: "bookmark.fill")
                            .foregroundColor(.orange)
                        VStack(alignment: .leading) {
                            Text(readingBookmarkText(readingBookmark))
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

            Section(header: Text("Add Reading Bookmark")) {
                Button("Random Ayah") {
                    let sura = Shared.QuranActionsUtils().getRandomSura()
                    let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                    Task {
                        _ = await viewModel.addReadingBookmark(sura: sura, ayah: ayah)
                    }
                }
                Button("Random Page") {
                    let page = Shared.QuranActionsUtils().getRandomPage()
                    Task {
                        _ = await viewModel.addPageReadingBookmark(page: page)
                    }
                }
            }
        }
    }

    private func readingBookmarkText(_ readingBookmark: Shared.ReadingBookmark) -> String {
        if let bookmark = readingBookmark as? Shared.AyahReadingBookmark {
            return "Surah \(bookmark.sura), Ayah \(bookmark.ayah)"
        }

        if let bookmark = readingBookmark as? Shared.PageReadingBookmark {
            return "Page \(bookmark.page)"
        }

        return "Reading bookmark"
    }
}
