import SwiftUI
import Shared

struct BookmarksTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    let readingBookmarks: [Shared.ReadingBookmark]
    private let slots: [Shared.ReadingBookmarkSlot] = [.coral, .teal, .indigo]
    
    var body: some View {
        List {
            ForEach(slots, id: \.name) { slot in
                Section(header: Text("\(slot.name.capitalized) Reading Bookmark")) {
                    if let readingBookmark = readingBookmarks.first(where: { $0.slot == slot }),
                       let locationText = readingBookmarkText(readingBookmark) {
                        HStack {
                            Image(systemName: "bookmark.fill")
                                .foregroundColor(.orange)
                            Text(locationText)
                            Spacer()
                            Button(action: {
                                Task {
                                    await viewModel.clearReadingBookmark(slot: slot)
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

                    Button("Random Ayah") {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        Task {
                            _ = await viewModel.setAyahReadingBookmark(
                                slot: slot,
                                sura: sura,
                                ayah: ayah
                            )
                        }
                    }
                    Button("Random Page") {
                        let page = Shared.QuranActionsUtils().getRandomPage()
                        Task {
                            _ = await viewModel.setPageReadingBookmark(slot: slot, page: page)
                        }
                    }
                }
            }
        }
    }

    private func readingBookmarkText(_ readingBookmark: Shared.ReadingBookmark) -> String? {
        if let bookmark = readingBookmark as? Shared.AyahReadingBookmark {
            return "Surah \(bookmark.sura), Ayah \(bookmark.ayah)"
        }

        if let bookmark = readingBookmark as? Shared.PageReadingBookmark {
            return "Page \(bookmark.page)"
        }

        return nil
    }
}
