package com.uma.workbench.protocol

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtocolHttpHeadersTest {
    @Test fun responseEntriesPreserveDuplicateValuesOrderAndOriginalName() {
        val fields = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 200 OK"),
            "Set-Cookie" to listOf("session=abc; Path=/", "tracking=xyz; Path=/api"),
            "X-Trace" to listOf("trace-one", "trace-two")
        )

        val entries = ProtocolHttpHeaders.responseEntries(fields)

        assertEquals(
            listOf(
                ProtocolHeader("Set-Cookie", "session=abc; Path=/"),
                ProtocolHeader("Set-Cookie", "tracking=xyz; Path=/api"),
                ProtocolHeader("X-Trace", "trace-one"),
                ProtocolHeader("X-Trace", "trace-two")
            ),
            entries
        )
        assertFalse(entries.any { it.name.startsWith("HTTP/") })
    }

    @Test fun requestEntriesUseAddRequestPropertyForEveryOccurrence() {
        val connection = RecordingHttpURLConnection()
        val entries = listOf(
            ProtocolHeader("Cookie", "one=1"),
            ProtocolHeader("Cookie", "two=2"),
            ProtocolHeader("X-Trace", "trace")
        )

        ProtocolHttpHeaders.addRequestEntries(connection, entries)

        assertEquals(entries.map { it.name to it.value }, connection.added)
    }

    private class RecordingHttpURLConnection : HttpURLConnection(URL("http://127.0.0.1/")) {
        val added = mutableListOf<Pair<String, String>>()

        override fun addRequestProperty(key: String, value: String) {
            added += key to value
        }

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }
}
