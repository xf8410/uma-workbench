package com.uma.workbench.protocol

import java.net.HttpURLConnection

/** Header conversion used by the direct HTTP transport. */
object ProtocolHttpHeaders {
    /**
     * Adds every request header occurrence instead of replacing an earlier value with the same
     * name. Entry order and original name casing are passed to HttpURLConnection unchanged.
     */
    fun addRequestEntries(
        connection: HttpURLConnection,
        entries: List<ProtocolHeader>
    ) {
        entries.forEach { entry ->
            connection.addRequestProperty(entry.name, entry.value)
        }
    }

    /**
     * Flattens HttpURLConnection's response field lists without joining duplicate values. The
     * null status-line key is not an HTTP header and is deliberately excluded.
     */
    fun responseEntries(fields: Map<String?, List<String>>): List<ProtocolHeader> = buildList {
        fields.forEach { (name, values) ->
            if (name != null) {
                values.forEach { value -> add(ProtocolHeader(name, value)) }
            }
        }
    }
}
