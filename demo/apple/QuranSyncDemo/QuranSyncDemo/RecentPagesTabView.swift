import SwiftUI
import Shared

struct RecentPagesTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    var body: some View {
        NavigationView {
            Group {
                if viewModel.recentPages.isEmpty {
                    VStack {
                        Spacer()
                        Text("No recent pages yet.")
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                } else {
                    List {
                        ForEach(viewModel.recentPages.indices, id: \.self) { index in
                            let page = viewModel.recentPages[index]
                            HStack {
                                Image(systemName: "clock")
                                    .foregroundColor(.blue)
                                VStack(alignment: .leading) {
                                    Text("Page \(page.page)")
                                        .font(.body)
                                    Text("First Ayah: \(page.chapterNumber):\(page.verseNumber)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Recent Pages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        Task {
                            let page = Int32.random(in: 1...604)
                            let sura = Int32.random(in: 1...114)
                            let ayah = Int32.random(in: 1...7)
                            _ = await viewModel.addRecentPage(
                                page: page,
                                firstAyahSura: sura,
                                firstAyahVerse: ayah
                            )
                        }
                    }) {
                        Image(systemName: "plus")
                    }
                }
            }
        }
    }
}
