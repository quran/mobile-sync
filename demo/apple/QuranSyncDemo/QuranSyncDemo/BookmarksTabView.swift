import SwiftUI
import Shared

struct BookmarksTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Bookmarks")
                Spacer()
                HStack(spacing: 16) {
                    Button(action: {
                        let randomPage = Shared.QuranActionsUtils().getRandomPage()
                        Task {
                            _ = await viewModel.addBookmark(page: randomPage)
                        }
                    }) {
                        Image(systemName: "plus.app")
                    }
                    Button(action: {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        Task {
                            _ = await viewModel.addBookmark(sura: sura, ayah: ayah)
                        }
                    }) {
                        Image(systemName: "plus.square")
                    }
                }
            }) {
                if viewModel.bookmarks.isEmpty {
                    Text("No bookmarks yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.bookmarks, id: \.self) { bookmark in
                        HStack {
                            Image(systemName: "bookmark.fill")
                                .foregroundColor(.accentColor)
                            
                            VStack(alignment: .leading) {
                                if let pageBookmark = bookmark as? Shared.Bookmark.PageBookmark {
                                    Text("Page \(pageBookmark.page)")
                                        .font(.body)
                                } else if let ayahBookmark = bookmark as? Shared.Bookmark.AyahBookmark {
                                    Text("Surah \(ayahBookmark.sura), Ayah \(ayahBookmark.ayah)")
                                        .font(.body)
                                }
                                
                                let date = (bookmark as? Shared.Bookmark.PageBookmark)?.lastUpdated ?? (bookmark as? Shared.Bookmark.AyahBookmark)?.lastUpdated
                                if let date = date {
                                    Text("\(dateFormatter.string(from: date))")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
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
