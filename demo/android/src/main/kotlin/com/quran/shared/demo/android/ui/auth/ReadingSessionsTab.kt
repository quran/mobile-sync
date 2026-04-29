package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quran.shared.persistence.model.ReadingSession

@Composable
fun ReadingSessionsTab(
    readingSessions: List<ReadingSession>,
    onAddReadingSession: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reading Sessions",
                style = MaterialTheme.typography.titleLarge
            )
            
            Button(onClick = onAddReadingSession) {
                Text("Add Random")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (readingSessions.isEmpty()) {
            EmptyListMessage("No reading sessions yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(readingSessions) { readingSession ->
                    ReadingSessionItem(readingSession = readingSession)
                }
            }
        }
    }
}

@Composable
fun ReadingSessionItem(readingSession: ReadingSession) {
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
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = "Surah ${readingSession.chapterNumber}, Ayah ${readingSession.verseNumber}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
