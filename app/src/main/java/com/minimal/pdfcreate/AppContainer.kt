package com.minimal.pdfcreate

import android.content.Context
import com.minimal.pdfcreate.data.DocumentRepository

/**
 * Hand-rolled service locator. A DI framework would be more than this app needs; one
 * repository created once against the application context is enough.
 */
object AppContainer {
    lateinit var repository: DocumentRepository
        private set

    fun init(context: Context) {
        if (!::repository.isInitialized) {
            repository = DocumentRepository(context.applicationContext)
        }
    }
}
