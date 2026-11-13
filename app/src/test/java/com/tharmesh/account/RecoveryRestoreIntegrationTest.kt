package com.tharmesh.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

/** Category 2: recovery restore path with real IdentityRecovery seal/open. */
class RecoveryRestoreIntegrationTest {

    @Test
    fun correctPhrase_opensSealedBlob() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        val opened = IdentityRecovery.open(sealed, phrase)
        assertNotNull(opened)
    }

    @Test
    fun wrongPhrase_failsClosed() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        assertNull(IdentityRecovery.open(sealed, IdentityRecovery.generatePhrase()))
    }

    @Test
    fun damagedCiphertext_failsClosed() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        val bad = sealed.copy(ciphertextB64 = "YQ==")
        assertNull(IdentityRecovery.open(bad, phrase))
    }
}
