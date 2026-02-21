package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quran.shared.persistence.model.Note

@Composable
fun NotesTab(
    notes: List<Note>,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newNoteBody by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Note") },
            text = {
                OutlinedTextField(
                    value = newNoteBody,
                    onValueChange = { newNoteBody = it },
                    label = { Text("Note Content") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNoteBody.isNotBlank()) {
                        onAddNote(newNoteBody)
                        newNoteBody = ""
                        showAddDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Notes",
                style = MaterialTheme.typography.titleLarge
            )
            
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Note")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (notes.isEmpty()) {
            EmptyListMessage("No notes yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes) { note ->
                    NoteItem(
                        note = note,
                        onDelete = { onDeleteNote(note.localId) }
                    )
                }
            }
        }
    }
}

@Composable
fun NoteItem(
    note: Note,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.body ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Ayah ${note.startAyahId} - ${note.endAyahId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Note",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
