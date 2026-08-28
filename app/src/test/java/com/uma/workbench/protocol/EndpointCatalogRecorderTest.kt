package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointCatalogRecorderTest {

    @Test
    fun `extracts top-level json keys in order`() {
        val keys = EndpointCatalogRecorder.jsonKeys("""{"a":1,"b":{"x":2},"c":[1,2,3]}""")
        assertEquals("a,b,c", keys)
    }

    @Test
    fun `dedupes repeated keys`() {
        val keys = EndpointCatalogRecorder.jsonKeys("""{"a":1,"a":2,"b":3}""")
        assertEquals("a,b", keys)
    }

    @Test
    fun `returns null for array body`() {
        assertNull(EndpointCatalogRecorder.jsonKeys("[1,2,3]"))
    }

    @Test
    fun `returns null for blank body`() {
        assertNull(EndpointCatalogRecorder.jsonKeys(""))
    }

    @Test
    fun `returns null for non object text`() {
        assertNull(EndpointCatalogRecorder.jsonKeys("hello world"))
    }

    @Test
    fun `limits extracted keys to 32`() {
        val body = (1..40).joinToString(",", prefix = "{", postfix = "}") { "\"k$it\":$it" }
        val keys = EndpointCatalogRecorder.jsonKeys(body) ?: ""
        assertEquals(32, keys.split(",").size)
    }
}
