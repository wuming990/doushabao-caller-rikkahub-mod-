package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Access to user-selected shared-storage folders through Android's Storage Access Framework.
 * No broad storage permission is requested; every root is explicitly granted by the user.
 */
class SharedStorageManager(
    private val context: Context,
) {
    private val resolver
        get() = context.contentResolver

    fun persistTreeUri(uri: Uri, readOnly: Boolean = false) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (readOnly) 0 else android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
    }

    fun releaseTreeUri(uri: Uri, readOnly: Boolean = false) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (readOnly) 0 else android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    fun rootDocumentId(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun queryRoot(treeUri: Uri): SharedStorageEntry? =
        queryDocument(treeUri, rootDocumentId(treeUri))

    fun listChildren(treeUri: Uri, parentDocumentId: String): List<SharedStorageEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        return queryEntries(treeUri, childrenUri)
    }

    fun createFolder(treeUri: Uri, parentDocumentId: String, name: String): Uri? =
        DocumentsContract.createDocument(
            resolver,
            documentUri(treeUri, parentDocumentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )

    fun createFile(
        treeUri: Uri,
        parentDocumentId: String,
        mimeType: String,
        name: String,
    ): Uri? = DocumentsContract.createDocument(
        resolver,
        documentUri(treeUri, parentDocumentId),
        mimeType,
        name,
    )

    fun delete(entry: SharedStorageEntry): Boolean =
        DocumentsContract.deleteDocument(resolver, entry.uri)

    fun rename(entry: SharedStorageEntry, name: String): Uri? =
        DocumentsContract.renameDocument(resolver, entry.uri, name)

    suspend fun importFile(
        treeUri: Uri,
        parentDocumentId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
    ): SharedStorageEntry = withContext(Dispatchers.IO) {
        val targetUri = createFile(
            treeUri = treeUri,
            parentDocumentId = parentDocumentId,
            mimeType = mimeType,
            name = displayName,
        ) ?: error("Unable to create destination file")

        val input = resolver.openInputStream(sourceUri)
            ?: error("Unable to open source file")
        val output = resolver.openOutputStream(targetUri)
            ?: error("Unable to open destination file")
        input.use { source ->
            output.use { destination ->
                source.copyTo(destination)
            }
        }
        queryDocument(treeUri, DocumentsContract.getDocumentId(targetUri))
            ?: error("Imported file is not available")
    }

    fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    fun mimeType(uri: Uri): String =
        resolver.getType(uri) ?: "application/octet-stream"

    fun openInputStream(uri: Uri) = resolver.openInputStream(uri)

    private fun queryDocument(treeUri: Uri, documentId: String): SharedStorageEntry? {
        val uri = documentUri(treeUri, documentId)
        return queryEntries(treeUri, uri).firstOrNull()
    }

    private fun queryEntries(treeUri: Uri, uri: Uri): List<SharedStorageEntry> =
        queryEntries(uri) { id -> documentUri(treeUri, id) }

    private fun queryEntries(uri: Uri, uriFor: (String) -> Uri): List<SharedStorageEntry> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            buildList {
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIndex) ?: "application/octet-stream"
                    add(
                        SharedStorageEntry(
                            documentId = cursor.getString(idIndex),
                            name = cursor.getString(nameIndex) ?: "Unnamed",
                            mimeType = mime,
                            sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                cursor.getLong(sizeIndex)
                            } else {
                                null
                            },
                            lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                cursor.getLong(modifiedIndex)
                            } else {
                                null
                            },
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) {
                                cursor.getInt(flagsIndex)
                            } else {
                                0
                            },
                            uri = uriFor(cursor.getString(idIndex)),
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}

data class SharedStorageEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val isDirectory: Boolean,
    val flags: Int,
    val uri: Uri = Uri.EMPTY,
)
