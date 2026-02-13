import SwiftUI
import Shared

struct CollectionsTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    @State private var showAddDialog = false
    @State private var newCollectionName = ""
    
    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Collections")
                Spacer()
                Button(action: { showAddDialog = true }) {
                    Image(systemName: "plus.rectangle.on.folder")
                }
            }) {
                if viewModel.collections.isEmpty {
                    Text("No collections yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.collections, id: \.localId) { collection in
                        CollectionRowView(viewModel: viewModel, collection: collection)
                    }
                }
            }
        }
        .alert("New Collection", isPresented: $showAddDialog) {
            TextField("Name", text: $newCollectionName)
            Button("Add") {
                if !newCollectionName.isEmpty {
                    viewModel.addCollection(name: newCollectionName)
                    newCollectionName = ""
                }
            }
            Button("Cancel", role: .cancel) {
                newCollectionName = ""
            }
        }
    }
}

struct CollectionRowView: View {
    @ObservedObject var viewModel: SyncViewModel
    let collection: Shared.Collection_
    @State private var bookmarks: [Shared.CollectionBookmark] = []
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundColor(.orange)
                    Text(collection.name)
                        .font(.headline)
                    Spacer()
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation {
                        isExpanded.toggle()
                    }
                }
                
                Button(action: { viewModel.deleteCollection(localId: collection.localId) }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                }
                .buttonStyle(BorderlessButtonStyle())
            }

            if isExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    if bookmarks.isEmpty {
                        Text("No bookmarks in this collection")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.leading, 32)
                    } else {
                        ForEach(bookmarks, id: \.localId) { cb in
                            HStack {
                                Image(systemName: "bookmark")
                                    .font(.caption)
                                Text(displayText(for: cb))
                                    .font(.subheadline)
                            }
                            .padding(.leading, 32)
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
        .task {
            do {
                for try await list in viewModel.bookmarksForCollection(collectionId: collection.localId) {
                    self.bookmarks = list as? [Shared.CollectionBookmark] ?? []
                }
            } catch {
                print("Error observing bookmarks for collection: \(error)")
            }
        }
    }
    
    private func displayText(for cb: Shared.CollectionBookmark) -> String {
        if let pb = cb as? Shared.CollectionBookmark.PageBookmark {
            return "Page \(pb.page)"
        } else if let ab = cb as? Shared.CollectionBookmark.AyahBookmark {
            return "Sura \(ab.sura), Ayah \(ab.ayah)"
        }
        return ""
    }
}
