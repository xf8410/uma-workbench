package com.uma.workbench.ui

import android.net.Uri
import com.uma.workbench.protocol.ProtocolArchiveImportResult
import com.uma.workbench.protocol.ProtocolHistoryStore
import com.uma.workbench.protocol.ProtocolHistoryTransfer

private fun MainViewModel.protocolHistoryTransfer(): ProtocolHistoryTransfer {
    val context = getApplication<android.app.Application>()
    val store = ProtocolHistoryStore(context)
    return ProtocolHistoryTransfer(
        resolver = context.contentResolver,
        append = { record ->
            store.append(record)
            Unit
        },
        records = { store.all() }
    )
}

/** Exports every persistent protocol row to the exact SAF document selected by the user. */
suspend fun MainViewModel.exportProtocolHistoryJsonl(uri: Uri): Long =
    protocolHistoryTransfer().export(uri)

/** Imports all valid JSONL rows, retains complete line errors, then reloads the visible timeline. */
suspend fun MainViewModel.importProtocolHistoryJsonl(uri: Uri): ProtocolArchiveImportResult = try {
    protocolHistoryTransfer().importRecords(uri)
} finally {
    reloadProtocolHistory().join()
}
