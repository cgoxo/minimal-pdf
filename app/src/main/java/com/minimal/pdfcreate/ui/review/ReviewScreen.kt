package com.minimal.pdfcreate.ui.review

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.minimal.pdfcreate.imaging.Importer
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.ScanDocument
import com.minimal.pdfcreate.pdf.PdfExporter
import com.minimal.pdfcreate.ui.common.PageThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/** Page list for the current document: reorder, delete, jump into the editor, export. */
/** Matches the home screen's cap; the picker's own limit still wins if it is lower. */
private const val MAX_IMPORT_IMAGES = 30

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    docId: String,
    onAddPages: () -> Unit,
    onEditPage: (String) -> Unit,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val repo = AppContainer.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf<ScanDocument?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    /** Imports into *this* document, then re-reads it so the new pages appear in the strip. */
    fun runImport(work: suspend () -> Int) {
        if (importing) return
        importing = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { work() } }
            doc = repo.get(docId)
            importing = false
        }
    }

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
        if (uris.isNotEmpty()) runImport { Importer.importImages(context, repo, docId, uris) }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { Importer.importPdf(context, repo, docId, uri) }
    }

    LaunchedEffect(docId) { doc = repo.get(docId) }

    val current = doc
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { addMenuOpen = true },
                            enabled = !importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (importing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.AddAPhoto, null)
                                Text("  Add pages")
                            }
                        }
                        // The same three routes the home screen offers. Pages can arrive
                        // mid-document too, and having to back out to the home screen to
                        // import one is the kind of gap that makes an app feel unfinished.
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Scan pages") },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = { addMenuOpen = false; onAddPages() },
                            )
                            DropdownMenuItem(
                                text = { Text("Import images") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = {
                                    addMenuOpen = false
                                    pickImages.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = {
                                    addMenuOpen = false
                                    pickPdf.launch(arrayOf("application/pdf"))
                                },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val d = current ?: return@Button
                            if (d.pages.isEmpty() || exporting) return@Button
                            exporting = true
                            scope.launch {
                                val name = PdfExporter.fileName(d)
                                runCatching {
                                    withContext(Dispatchers.IO) { PdfExporter.export(context, repo, d) }
                                }.onSuccess {
                                    repo.save(d.copy(pdfFileName = name))
                                    exporting = false
                                    onSaved()
                                }.onFailure { exporting = false }
                            }
                        },
                        enabled = current?.pages?.isNotEmpty() == true && !exporting,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (exporting) "Saving..." else "Save PDF") }
                }
            }
        }
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = current.name,
                onValueChange = { doc = current.copy(name = it).also(repo::save) },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Tap a page to edit filters, drawing, text or crop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(current.pages, key = { it.id }) { page ->
                    val index = current.pages.indexOf(page)
                    Card {
                        Column {
                            PageThumbnail(
                                docId = docId,
                                page = page,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                    .clickable { onEditPage(page.id) },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                                )
                                IconButton(
                                    onClick = { doc = move(repo, current, index, -1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(36.dp),
                                ) { Icon(Icons.Default.ArrowBack, "Move earlier") }
                                IconButton(
                                    onClick = { doc = move(repo, current, index, 1) },
                                    enabled = index < current.pages.lastIndex,
                                    modifier = Modifier.size(36.dp),
                                ) { Icon(Icons.Default.ArrowForward, "Move later") }
                                IconButton(
                                    onClick = {
                                        repo.deletePage(docId, page.id)
                                        val remaining = repo.get(docId)
                                        if (remaining == null || remaining.pages.isEmpty()) {
                                            // Nothing left to review, and an empty document is
                                            // not something the user should have to delete.
                                            repo.delete(docId)
                                            onBack()
                                        } else {
                                            doc = remaining
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) { Icon(Icons.Default.Delete, "Delete page") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun move(
    repo: com.minimal.pdfcreate.data.DocumentRepository,
    doc: ScanDocument,
    index: Int,
    delta: Int,
): ScanDocument {
    val target = index + delta
    if (target !in doc.pages.indices) return doc
    val pages = doc.pages.toMutableList()
    val moved = pages.removeAt(index)
    pages.add(target, moved)
    val updated = doc.copy(pages = pages)
    repo.save(updated)
    return updated
}
