package com.minimal.pdfcreate.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Documents live entirely in app-private storage as
 * `filesDir/docs/<docId>/{manifest.json, page_*.jpg}`.
 *
 * Only the *exported* PDF is written to the public Documents/Scanly folder, so the
 * editable project survives without needing any storage permission.
 */
class DocumentRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val appContext = context.applicationContext
    private val root = File(context.filesDir, "docs").apply { mkdirs() }
    val signaturesDir = File(context.filesDir, "signatures").apply { mkdirs() }

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val loaded = root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> runCatching { readManifest(dir) }.getOrNull() }
            ?: emptyList()

        // A document only ever exists once it has a page (the camera creates it on the first
        // capture), so anything with zero pages is abandoned and gets swept up here rather
        // than left on the home screen for the user to tidy by hand.
        val (empty, real) = loaded.partition { it.pages.isEmpty() }
        empty.forEach { doc ->
            doc.pdfFileName?.let { runCatching { PdfStore.delete(appContext, it) } }
            docDir(doc.id).deleteRecursively()
        }
        _documents.value = reconcileExports(real).sortedByDescending { it.updatedAt }
    }

    /**
     * Drops [ScanDocument.pdfFileName] for any document whose exported PDF is no longer in
     * `Documents/Scanly`.
     *
     * The folder is a public one: the user can delete a PDF from Files, from a desktop over
     * USB, or from anywhere else, and nothing tells us when they do. Without this the card
     * stayed on the home screen still claiming to be exported, and tapping it opened a viewer
     * with nothing behind it. Reverting the document to a draft keeps the pages — which are
     * ours, in private storage, and were never deleted — and makes the card open the editor,
     * so the fix for a deleted PDF is simply to save it again.
     *
     * One MediaStore query for the whole folder, and only when something claims to be
     * exported; the manifests are rewritten in place so the correction survives a restart.
     */
    private fun reconcileExports(docs: List<ScanDocument>): List<ScanDocument> {
        if (docs.none { it.pdfFileName != null }) return docs
        val present = runCatching { PdfStore.list(appContext).mapTo(HashSet()) { it.displayName } }
            .getOrElse { return docs }  // Query failed: assume nothing is missing.

        // Seeing *nothing* when documents claim to be exported is far more likely to mean the
        // query cannot see the folder — an ownership quirk, a storage volume not mounted yet —
        // than that the user deleted every PDF at once. Demoting every document on the
        // strength of an empty answer would be the worst possible reading of it, so an empty
        // result is treated as no information rather than as bad news. The viewer's
        // "Back to draft" button still rescues the one document the user actually opens.
        if (present.isEmpty()) return docs
        return docs.map { doc ->
            val name = doc.pdfFileName
            if (name == null || name in present) {
                doc
            } else {
                // Not a user edit, so updatedAt stays put: a file vanishing elsewhere should
                // not shuffle the document to the top of the list.
                doc.copy(pdfFileName = null).also(::writeManifest)
            }
        }
    }

    private fun writeManifest(doc: ScanDocument) {
        File(docDir(doc.id), MANIFEST)
            .writeText(json.encodeToString(ScanDocument.serializer(), doc))
    }

    private fun readManifest(dir: File): ScanDocument? {
        val manifest = File(dir, MANIFEST)
        if (!manifest.exists()) return null
        return json.decodeFromString(ScanDocument.serializer(), manifest.readText())
    }

    fun get(docId: String): ScanDocument? =
        _documents.value.firstOrNull { it.id == docId } ?: readManifest(docDir(docId))

    fun docDir(docId: String): File = File(root, docId).apply { mkdirs() }

    fun imageFile(docId: String, name: String): File = File(docDir(docId), name)

    /** Reserves a new, unused page image file inside the document folder. */
    fun newImageFile(docId: String): File =
        File(docDir(docId), "page_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.jpg")

    fun newSignatureFile(): File = File(signaturesDir, "sig_${UUID.randomUUID()}.png")

    fun signatures(): List<File> =
        signaturesDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * The document for [docId], or a fresh unsaved one. Nothing touches disk until the caller
     * saves it *with* a page — see [refresh].
     */
    fun getOrNew(docId: String): ScanDocument =
        get(docId) ?: ScanDocument(id = docId, name = defaultName())

    fun save(doc: ScanDocument) {
        writeManifest(doc.copy(updatedAt = System.currentTimeMillis()))
        refresh()
    }

    fun delete(docId: String) {
        docDir(docId).deleteRecursively()
        refresh()
    }

    fun deletePage(docId: String, pageId: String) {
        val doc = get(docId) ?: return
        val page = doc.page(pageId) ?: return
        imageFile(docId, page.imageName).delete()
        val remaining = doc.pages.filterNot { it.id == pageId }
        if (remaining.isEmpty() && doc.pdfFileName != null) {
            // A zero-page PDF is not a document, it is a bug waiting to be opened. Retire the
            // exported file and let the document fall back to being a draft.
            PdfStore.delete(appContext, doc.pdfFileName)
            save(doc.copy(pages = remaining, pdfFileName = null))
        } else {
            save(doc.copy(pages = remaining))
        }
    }

    fun deleteSignature(file: File) {
        file.delete()
    }

    companion object {
        private const val MANIFEST = "manifest.json"

        fun defaultName(): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return "Scanly_" + fmt.format(java.util.Date())
        }
    }
}
