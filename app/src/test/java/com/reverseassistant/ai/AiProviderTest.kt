package com.reverseassistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {
    @Test fun compatibleProvidersHaveUniqueSecretsAndHttpsEndpoints() {
        val providers = AiProvider.values().filter { it.endpoint != null }
        assertEquals(providers.size, providers.map { it.secretName }.toSet().size)
        assertTrue(providers.size >= 10)
        providers.forEach {
            assertTrue(it.endpoint!!.startsWith("https://"))
            assertTrue(it.defaultModel.isNotBlank())
        }
    }

    @Test fun customProviderRequiresUserConfiguredEndpoint() {
        assertEquals(null, AiProvider.CUSTOM_OPENAI_COMPATIBLE.endpoint)
    }
}
