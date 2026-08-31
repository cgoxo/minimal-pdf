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
 * Only the *exported* PDF is written to the public Documents/minimalPdf folder, so the
 * editable project survives without needing any storage permission.
 */
class DocumentRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

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
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        _documents.value = loaded
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

    fun createDocument(name: String = defaultName()): ScanDocument {
        val doc = ScanDocument(name = name)
        save(doc)
        return doc
    }

    fun save(doc: ScanDocument) {
        val updated = doc.copy(updatedAt = System.currentTimeMillis())
        File(docDir(doc.id), MANIFEST)
            .writeText(json.encodeToString(ScanDocument.serializer(), updated))
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
        save(doc.copy(pages = doc.pages.filterNot { it.id == pageId }))
    }

    companion object {
        private const val MANIFEST = "manifest.json"

        fun defaultName(): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return "Scan_" + fmt.format(java.util.Date())
        }
    }
}
