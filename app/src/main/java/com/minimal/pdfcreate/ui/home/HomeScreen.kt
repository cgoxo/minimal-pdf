package com.minimal.pdfcreate.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import com.minimal.pdfcreate.imaging.Importer
import com.minimal.pdfcreate.ui.common.PageThumbnail
import com.minimal.pdfcreate.ui.theme.ScanlyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android's own picker caps out around here, and so does anyone's patience for one import. */
private const val MAX_IMPORT_IMAGES = 30

/** Below this many documents the list is its own index, and a search box is just clutter. */
private const val SEARCH_THRESHOLD = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onImported: (String) -> Unit,
) {
    val repo = AppContainer.repository
    val context = LocalContext.current
    val documents by repo.documents.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ScanDocument?>(null) }
    var pendingRename by remember { mutableStateOf<ScanDocument?>(null) }
    var importing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Non-empty means selection mode. Long-press a card to enter it, back out to leave.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    // Matched on name only: it is the one thing the user chose and the one thing they will
    // remember. Case-insensitive, and a blank query means everything.
    val shown = remember(documents, query) {
        val q = query.trim()
        if (q.isEmpty()) documents
        else documents.filter { it.name.contains(q, ignoreCase = true) }
    }

    // A selected document that is then deleted elsewhere must not linger in the selection.
    LaunchedEffect(documents) {
        if (selected.isNotEmpty()) {
            val ids = documents.mapTo(HashSet()) { it.id }
            selected = selected.intersect(ids)
        }
    }

    fun shareMany(docs: List<ScanDocument>) {
        val uris = ArrayList<Uri>()
        docs.forEach { d -> d.pdfFileName?.let { PdfStore.findByName(context, it)?.let(uris::add) } }
        if (uris.isEmpty()) {
            scope.launch { snackbars.showSnackbar("Nothing to share — save these as PDFs first") }
            return
        }
        // One PDF goes out as SEND so the target sees a single file rather than a list of one.
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share PDFs"))
    }

    /**
     * Both importers do the same dance: make a document id, decode on IO, then either open
     * the new draft or say why nothing appeared. A document with no pages is swept away by
     * [DocumentRepository.refresh], so a failed import leaves nothing behind.
     */
    fun runImport(work: suspend (String) -> Int) {
        if (importing) return
        importing = true
        scope.launch {
            val docId = java.util.UUID.randomUUID().toString()
            val added = withContext(Dispatchers.IO) { runCatching { work(docId) }.getOrDefault(0) }
            importing = false
            if (added > 0) onImported(docId) else snackbars.showSnackbar("Nothing could be imported")
        }
    }

    // The system photo picker refuses a selection cap above its own, and the contract asserts
    // on that rather than clamping — so ask it what the ceiling is before naming one.
    val maxImages = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            minOf(MAX_IMPORT_IMAGES, MediaStore.getPickImagesMaxLimit())
        } else {
            MAX_IMPORT_IMAGES
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        remember(maxImages) { ActivityResultContracts.PickMultipleVisualMedia(maxImages) }
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) runImport { docId -> Importer.importImages(context, repo, docId, uris) }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { docId -> Importer.importPdf(context, repo, docId, uri) }
    }

    LaunchedEffect(Unit) { repo.refresh() }

    Scaffold(
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text("Scanly") },
                    actions = {
                        Text(
                            "Documents/${PdfStore.FOLDER}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                )
            } else {
                // The bar becomes the selection's own toolbar rather than growing a second
                // row of controls: what the buttons act on is then never in doubt.
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selected = if (selected.size == shown.size) emptySet()
                            else shown.mapTo(HashSet()) { it.id }
                        }) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(onClick = {
                            shareMany(documents.filter { it.id in selected })
                        }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { confirmBulkDelete = true }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            // Nothing to add while a selection is live — the bar at the top owns the screen.
            if (selected.isEmpty()) {
                AddMenu(
                    busy = importing,
                    onScan = { onCreate(java.util.UUID.randomUUID().toString()) },
                    onPickImages = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // The search field is offered once there is enough to search. Below three
            // documents you can see them all, and a box asking what you are looking for is
            // just something else in the way.
            if (documents.size >= SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search documents") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            when {
                documents.isEmpty() -> EmptyState(Modifier.fillMaxSize())

                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No document matches \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp,
                        start = 12.dp, end = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.id }) { doc ->
                        val isSelected = doc.id in selected
                        DocumentCard(
                            doc = doc,
                            selectionMode = selected.isNotEmpty(),
                            isSelected = isSelected,
                            // While selecting, a tap toggles instead of opening: nobody wants
                            // to be thrown into a viewer half way through picking files.
                            onOpen = {
                                if (selected.isNotEmpty()) {
                                    selected = if (isSelected) selected - doc.id else selected + doc.id
                                } else if (doc.pdfFileName != null) {
                                    onOpen(doc.id)
                                } else {
                                    onEdit(doc.id)
                                }
                            },
                            onLongPress = {
                                selected = if (isSelected) selected - doc.id else selected + doc.id
                            },
                            onEdit = { onEdit(doc.id) },
                            onRename = { pendingRename = doc },
                            onShare = { shareMany(listOf(doc)) },
                            onDelete = { pendingDelete = doc },
                        )
                    }
                }
            }
        }
    }

    // Leaving selection mode is what back should do first, ahead of leaving the screen.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    if (confirmBulkDelete) {
        val victims = documents.filter { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${victims.size} documents?") },
            text = { Text("Their scanned pages and exported PDFs will both be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    victims.forEach { d ->
                        d.pdfFileName?.let { PdfStore.delete(context, it) }
                        repo.delete(d.id)
                    }
                    selected = emptySet()
                    confirmBulkDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") }
            },
        )
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

/**
 * The + button, expanding upwards into the three ways a document can start.
 *
 * Scan stays the primary action — it is the bottom entry, nearest the thumb and nearest the
 * button that opened the menu — with the two import routes stacked above it.
 */
@Composable
private fun AddMenu(
    busy: Boolean,
    onScan: () -> Unit,
    onPickImages: () -> Unit,
    onPickPdf: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = open && !busy,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                AddMenuItem(Icons.Default.PictureAsPdf, "Import PDF") { open = false; onPickPdf() }
                AddMenuItem(Icons.Default.Image, "Import images") { open = false; onPickImages() }
                AddMenuItem(Icons.Default.PhotoCamera, "Scan pages") { open = false; onScan() }
            }
        }
        FloatingActionButton(onClick = { if (!busy) open = !open }) {
            when {
                // Importing a long PDF is not instant, and the spinner sits where the control
                // that started it was, so it is obvious what is busy.
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                open -> Icon(Icons.Default.Close, contentDescription = "Close menu")
                else -> Icon(Icons.Default.Add, contentDescription = "New PDF")
            }
        }
    }
}

@Composable
private fun AddMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The label is part of the target: a bare icon mini-FAB is a guessing game.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(end = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        SmallFloatingActionButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "No PDFs yet.\nTap + to scan a document, or import images or a PDF.",
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
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val date = remember(doc.updatedAt) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(doc.updatedAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    // The whole card is the target; the box is an indicator that happens to
                    // be tappable, not the only way in.
                    onCheckedChange = { onLongPress() },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
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
            // The overflow menu is noise while selecting: the toolbar owns the actions.
            if (!selectionMode) Box {
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
    ScanlyTheme(dynamicColor = false) {
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
    ScanlyTheme(dynamicColor = false) {
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
    ScanlyTheme(dynamicColor = false) {
        Surface { EmptyState(Modifier.fillMaxSize()) }
    }
}
