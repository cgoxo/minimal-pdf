package com.minimal.pdfcreate.ui.home

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.Page
import com.minimal.pdfcreate.data.PdfStore
import com.minimal.pdfcreate.data.ScanDocument
import com.minimal.pdfcreate.ui.common.PageThumbnail
import com.minimal.pdfcreate.ui.theme.MinimalPdfTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val repo = AppContainer.repository
    val context = LocalContext.current
    val documents by repo.documents.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ScanDocument?>(null) }
    var pendingRename by remember { mutableStateOf<ScanDocument?>(null) }

    LaunchedEffect(Unit) { repo.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MinimalPDF") },
                actions = {
                    Text(
                        "Documents/${PdfStore.FOLDER}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onCreate(repo.createDocument().id) }) {
                Icon(Icons.Default.Add, contentDescription = "New PDF")
            }
        }
    ) { padding ->
        if (documents.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                    start = 12.dp, end = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentCard(
                        doc = doc,
                        onOpen = { if (doc.pdfFileName != null) onOpen(doc.id) else onEdit(doc.id) },
                        onEdit = { onEdit(doc.id) },
                        onRename = { pendingRename = doc },
                        onShare = {
                            doc.pdfFileName?.let { name ->
                                PdfStore.findByName(context, name)?.let { uri ->
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share PDF"))
                                }
                            }
                        },
                        onDelete = { pendingDelete = doc },
                    )
                }
            }
        }
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${doc.name}\"?") },
            text = { Text("The scanned pages and the exported PDF will both be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    doc.pdfFileName?.let { PdfStore.delete(context, it) }
                    repo.delete(doc.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    pendingRename?.let { doc ->
        var name by remember(doc.id) { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.save(doc.copy(name = name.ifBlank { doc.name }))
                    pendingRename = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "No PDFs yet.\nTap + to scan your first document.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentCard(
    doc: ScanDocument,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val date = remember(doc.updatedAt) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(doc.updatedAt))
    }

    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageThumbnail(
                docId = doc.id,
                page = doc.pages.firstOrNull(),
                modifier = Modifier
                    .size(width = 54.dp, height = 72.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(doc.pages.size)
                        append(if (doc.pages.size == 1) " page" else " pages")
                        append(" · ")
                        append(date)
                        if (doc.pdfFileName == null) append(" · draft")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    modifier = Modifier.clickable { menuOpen = true }.padding(4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit pages") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    if (doc.pdfFileName != null) {
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Previews. These render in Android Studio's split/design pane without a device, an install
// or a running app, so laying out a card is a two-second loop instead of a two-minute one.
// A preview function must be @Composable, take no parameters, and supply its own sample data.
// ---------------------------------------------------------------------------------------

private val SampleDocument = ScanDocument(
    id = "sample",
    name = "Lease agreement",
    pages = List(4) { Page(imageName = "page_$it.jpg") },
    pdfFileName = "Lease agreement.pdf",
)

@Preview(name = "Document card", showBackground = true, widthDp = 380)
@Preview(
    name = "Document card · dark",
    showBackground = true,
    widthDp = 380,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DocumentCardPreview() {
    MinimalPdfTheme(dynamicColor = false) {
        Surface {
            Box(Modifier.padding(12.dp)) {
                DocumentCard(
                    doc = SampleDocument,
                    onOpen = {}, onEdit = {}, onRename = {}, onShare = {}, onDelete = {},
                )
            }
        }
    }
}

@Preview(name = "Document card · draft", showBackground = true, widthDp = 380)
@Composable
private fun DraftCardPreview() {
    MinimalPdfTheme(dynamicColor = false) {
        Surface {
            Box(Modifier.padding(12.dp)) {
                DocumentCard(
                    doc = SampleDocument.copy(name = "Scan_20260831_143002", pdfFileName = null),
                    onOpen = {}, onEdit = {}, onRename = {}, onShare = {}, onDelete = {},
                )
            }
        }
    }
}

@Preview(name = "Home · empty", showBackground = true, widthDp = 380, heightDp = 360)
@Composable
private fun EmptyStatePreview() {
    MinimalPdfTheme(dynamicColor = false) {
        Surface { EmptyState(Modifier.fillMaxSize()) }
    }
}
