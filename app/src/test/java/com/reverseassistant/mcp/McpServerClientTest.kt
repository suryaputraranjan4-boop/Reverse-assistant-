package com.reverseassistant.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpServerClientTest {
    @Test
    fun acceptsHttpsEndpoints() {
        assertEquals(
            "https://example.com/mcp",
            McpServerClient.validateEndpoint("https://example.com/mcp").toString()
        )
    }

    @Test
    fun permitsLoopbackHttpForLocalDevelopment() {
        assertEquals(
            "http://localhost:8080/mcp",
            McpServerClient.validateEndpoint("http://localhost:8080/mcp").toString()
        )
    }

    @Test
    fun rejectsInsecureRemoteHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerClient.validateEndpoint("http://example.com/mcp")
        }
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerClient.validateEndpoint("https://user:password@example.com/mcp")
        }
    }

    @Test
    fun rejectsUrlFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerClient.validateEndpoint("https://example.com/mcp#fragment")
        }
    }
}
