package com.uma.workbench.ui

import android.net.Uri
import com.uma.workbench.protocol.ProtocolArchiveImportResult
import com.uma.workbench.protocol.ProtocolHistoryStore
import com.uma.workbench.protocol.ProtocolHistoryTransfer

/** Exports every persistent protocol row to the exact SAF document selected by the user. */
suspend fun MainViewModel.exportProtocolHistoryJsonl(uri: Uri): Long {
    val context = getApplication<android.app.Application>()
    val store = ProtocolHistoryStore(context)
    return ProtocolHistoryTransfer(context.contentResolver, store::append, store::all).export(uri)
}

/** Imports all valid JSONL rows, retains complete line errors, then reloads the visible timeline. */
suspend fun MainViewModel.importProtocolHistoryJsonl(uri: Uri): ProtocolArchiveImportResult {
    val context = getApplication<android.app.Application>()
    val store = ProtocolHistoryStore(context)
    return try {
        ProtocolHistoryTransfer(context.contentResolver, store::append, store::all).importRecords(uri)
    } finally {
        reloadProtocolHistory().join()
    }
}
