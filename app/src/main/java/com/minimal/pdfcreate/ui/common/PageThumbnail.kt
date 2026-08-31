package com.minimal.pdfcreate.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import com.minimal.pdfcreate.AppContainer
import com.minimal.pdfcreate.data.Page
import com.minimal.pdfcreate.imaging.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a page through the normal pipeline at thumbnail size, off the main thread.
 *
 * `LocalInspectionMode` is true when this runs inside Android Studio's preview pane, where
 * there is no app process, no repository and no files on disk. Guarding on it lets anything
 * that contains a thumbnail stay previewable — the preview simply shows a placeholder.
 */
@Composable
fun PageThumbnail(docId: String, page: Page?, modifier: Modifier = Modifier) {
    val previewing = LocalInspectionMode.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        docId, page?.id, page?.filter, page?.crop, page?.rotation,
        page?.strokes?.size, page?.overlays?.size,
    ) {
        value = if (page == null || previewing) null else withContext(Dispatchers.Default) {
            runCatching {
                PageRenderer.render(AppContainer.repository, docId, page, PageRenderer.THUMB_DIM)
            }.getOrNull()
        }
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}
