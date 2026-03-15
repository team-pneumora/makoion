package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelProviderCredentialVaultTest {
    @Test
    fun `blank secrets are masked as empty`() {
        assertEquals("empty", maskProviderCredential("   "))
    }
}
