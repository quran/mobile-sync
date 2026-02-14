import SwiftUI
import Shared

struct NotesTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    @State private var showAddDialog = false
    @State private var newNoteBody = ""

    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Notes")
                Spacer()
                Button(action: { showAddDialog = true }) {
                    Image(systemName: "square.and.pencil")
                }
            }) {
                if viewModel.notes.isEmpty {
                    Text("No notes yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.notes, id: \.localId) { note in
                        HStack {
                            Image(systemName: "note.text")
                                .foregroundColor(.blue)
                            VStack(alignment: .leading) {
                                Text(note.body ?? "")
                                    .font(.body)
                                Text("Ayah \(note.startAyahId) - \(note.endAyahId)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(action: {
                                Task {
                                    await viewModel.deleteNote(localId: note.localId)
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
        .alert("New Note", isPresented: $showAddDialog) {
            TextField("Content", text: $newNoteBody)
            Button("Add") {
                let body = newNoteBody
                if !body.isEmpty {
                    Task {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        await viewModel.addNote(body: body, startAyahId: Int64(ayah), endAyahId: Int64(ayah))
                    }
                }
                newNoteBody = ""
            }
            Button("Cancel", role: .cancel) {
                newNoteBody = ""
            }
        }
    }
}
