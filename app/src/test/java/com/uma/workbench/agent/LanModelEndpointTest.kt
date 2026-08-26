package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanModelEndpointTest {

    @Test
    fun isPrivateNetworkAddress_localhost() {
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("localhost"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("127.0.0.1"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("::1"))
    }

    @Test
    fun isPrivateNetworkAddress_192168() {
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("192.168.0.1"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("192.168.1.100"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("192.168.255.255"))
    }

    @Test
    fun isPrivateNetworkAddress_10x() {
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("10.0.0.1"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("10.255.255.255"))
    }

    @Test
    fun isPrivateNetworkAddress_172_16to31() {
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("172.16.0.1"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("172.31.255.255"))
        assertFalse("172.15 should not be private", LanModelEndpoint.isPrivateNetworkAddress("172.15.0.1"))
        assertFalse("172.32 should not be private", LanModelEndpoint.isPrivateNetworkAddress("172.32.0.1"))
    }

    @Test
    fun isPrivateNetworkAddress_dotLocal() {
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("my-pc.local"))
        assertTrue(LanModelEndpoint.isPrivateNetworkAddress("nas.local"))
    }

    @Test
    fun isPrivateNetworkAddress_publicAddress() {
        assertFalse(LanModelEndpoint.isPrivateNetworkAddress("8.8.8.8"))
        assertFalse(LanModelEndpoint.isPrivateNetworkAddress("api.openai.com"))
    }

    @Test
    fun validate_httpLan_ok() {
        val ep = LanModelEndpoint(
            baseUrl = "http://192.168.1.100:11434",
            model = "llama3"
        )
        ep.validate() // should not throw
    }

    @Test
    fun validate_https_ok() {
        val ep = LanModelEndpoint(
            baseUrl = "https://my-server.example.com",
            model = "llama3"
        )
        ep.validate() // should not throw
    }

    @Test
    fun validate_httpPublic_fails() {
        val ep = LanModelEndpoint(
            baseUrl = "http://api.openai.com",
            model = "gpt-4"
        )
        try {
            ep.validate()
            fail("Should have rejected HTTP for public address")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("HTTP 仅允许用于局域网"))
        }
    }

    @Test
    fun validate_blankBaseUrl_fails() {
        val ep = LanModelEndpoint(baseUrl = "", model = "llama3")
        try {
            ep.validate()
            fail("Should reject blank URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不能为空"))
        }
    }

    @Test
    fun validate_blankModel_fails() {
        val ep = LanModelEndpoint(baseUrl = "http://localhost:8080", model = "")
        try {
            ep.validate()
            fail("Should reject blank model")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不能为空"))
        }
    }

    @Test
    fun chatUrl_appendsV1Path() {
        val ep = LanModelEndpoint(baseUrl = "http://192.168.1.100:11434", model = "llama3")
        assertEquals("http://192.168.1.100:11434/v1/chat/completions", ep.chatUrl())
    }

    @Test
    fun chatUrl_respectsExplicitPath() {
        val ep = LanModelEndpoint(baseUrl = "http://localhost:8080/v1/chat/completions", model = "llama3")
        assertEquals("http://localhost:8080/v1/chat/completions", ep.chatUrl())
    }

    @Test
    fun modelsUrl_appendsV1Path() {
        val ep = LanModelEndpoint(baseUrl = "http://192.168.1.100:11434", model = "llama3")
        assertEquals("http://192.168.1.100:11434/v1/models", ep.modelsUrl())
    }

    @Test
    fun configured_requiresBaseUrlAndModel() {
        assertFalse(LanModelEndpoint().configured)
        assertFalse(LanModelEndpoint(baseUrl = "http://localhost:8080").configured)
        assertFalse(LanModelEndpoint(model = "llama3").configured)
        assertTrue(LanModelEndpoint(baseUrl = "http://localhost:8080", model = "llama3").configured)
    }
}
