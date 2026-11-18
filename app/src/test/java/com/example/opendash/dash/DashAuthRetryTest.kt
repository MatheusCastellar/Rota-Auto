package com.example.opendash.dash

import com.example.opendash.dash.protocol.DashCommands
import com.example.opendash.dash.protocol.Tlv
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF) // Host JCE avoids Windows signed-provider verification failure.
class DashAuthRetryTest {
    @Test fun retryKeepsPartialPublicKeyAndResendsTheSameEncryptedSessionKey() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public as RSAPublicKey
        val auth = DashAuth("RE_TEST")
        assertArrayEquals(DashCommands.authRequest(), auth.retryPacket())
        assertEquals(AuthEvent.None, auth.ingest(Tlv(7, 0, publicKey.modulus.toByteArray())))
        assertArrayEquals(DashCommands.authRequest(), auth.retryPacket())
        val event = auth.ingest(Tlv(7, 3, publicKey.publicExponent.toByteArray())) as AuthEvent.SendKey
        val sessionKey = auth.sessionKey!!.copyOf()
        assertArrayEquals(event.packet, auth.retryPacket())
        auth.retryPacket().fill(0)
        assertArrayEquals(event.packet, auth.retryPacket())
        assertArrayEquals(sessionKey, auth.sessionKey)
        assertEquals(AuthEvent.None, auth.ingest(Tlv(7, 0, publicKey.modulus.toByteArray())))
        assertEquals(AuthEvent.Rejected, auth.ingest(Tlv(7, 1, byteArrayOf(0))))
        auth.reset()
        assertArrayEquals(DashCommands.authRequest(), auth.retryPacket())
        auth.ingest(Tlv(7, 0, publicKey.modulus.toByteArray()))
        assertTrue(auth.ingest(Tlv(7, 3, publicKey.publicExponent.toByteArray())) is AuthEvent.SendKey)
        assertFalse(sessionKey.contentEquals(auth.sessionKey!!))
    }

    @Test fun lostInitialReplyIsRetriedAfterTwoSecondsAndStopsOnConfirmation() = runBlocking {
        var time = 0L
        var confirmed = false
        val retries = mutableListOf<Long>()
        assertTrue(awaitDashAuthentication(15_000, { time }, { time += it }, { true }, { confirmed }) {
            retries += time
            confirmed = true
        })
        assertEquals(listOf(2_000L), retries)
    }

    @Test fun silentDashHasBoundedRetriesAndOriginalTimeout() = runBlocking {
        var time = 0L
        val retries = mutableListOf<Long>()
        assertFalse(awaitDashAuthentication(15_000, { time }, { time += it }, { true }, { false }) {
            retries += time
        })
        assertEquals((1L..7L).map { it * 2_000L }, retries)
        assertEquals(15_000L, time)
    }

    @Test fun replacedTransportCannotRetryOrSucceedFromOldConfirmation() = runBlocking {
        var time = 0L
        assertFalse(awaitDashAuthentication(15_000, { time }, { time += it }, { time < 1_000 }, { time >= 1_000 }) {
            fail("Old transport retried")
        })
        assertEquals(1_000L, time)
    }

    @Test fun cancellationDoesNotRetryOrBecomeAnAuthenticationFailure() = runBlocking {
        var cancelled = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitDashAuthentication(15_000, { 0L }, { delay(it) }, { true }, { false }) {
                    fail("Cancelled session retried")
                }
                fail("Cancellation treated as auth result")
            } catch (_: CancellationException) { cancelled = true }
        }
        task.cancelAndJoin()
        assertTrue(cancelled)
    }
}
