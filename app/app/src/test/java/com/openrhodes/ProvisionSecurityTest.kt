package com.openrhodes

import com.google.crypto.tink.subtle.X25519
import com.openrhodes.data.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ProvisionSecurityTest {
    private val pop = "a53e2c109f67b824"

    private class Device(val client: ProvisionSecurity, val pop: String?) {
        val key = X25519.generatePrivateKey()
        val public = X25519.publicFromPrivate(key)
        lateinit var clientPublic: ByteArray
        lateinit var cipher: Cipher

        fun response0(): ByteArray {
            val hello = client.hello()
            // Golden prefix from Espressif session.proto/sec1.proto: scheme 1,
            // sec1 payload, command0 field 20, and a 32-byte Curve25519 key.
            assertArrayEquals(
                byteArrayOf(0x10, 0x01, 0x5a, 0x25, 0xa2.toByte(), 0x01, 0x22, 0x0a, 0x20),
                hello.copyOfRange(0, 9),
            )
            assertEquals(1L, Proto.number(hello, 2))
            clientPublic = Proto.field(Proto.field(Proto.field(hello, 11), 20), 1)
            val shared = X25519.computeSharedSecret(key, clientPublic)
            if (pop != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(pop.toByteArray())
                for (i in shared.indices) shared[i] =
                    (shared[i].toInt() xor digest[i].toInt()).toByte()
            }
            val iv = ByteArray(16) { it.toByte() }
            cipher =
                Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(shared, "AES"), IvParameterSpec(iv))
                }
            return envelope(1, 21, Proto.bytes(2, public) + Proto.bytes(3, iv))
        }

        fun response1(request: ByteArray): ByteArray {
            val challenge = Proto.field(Proto.field(Proto.field(request, 11), 22), 2)
            assertArrayEquals(public, cipher.update(challenge))
            return envelope(3, 23, Proto.bytes(3, cipher.update(clientPublic)))
        }

        private fun envelope(message: Long, field: Int, body: ByteArray) =
            Proto.integer(2, 1) +
                Proto.bytes(11, Proto.integer(1, message) + Proto.bytes(field, body))
    }

    @Test
    fun completeEspressifHandshakeAndBidirectionalStream() {
        val client = ProvisionSecurity(pop)
        val device = Device(client, pop)
        client.finish(device.response1(client.verify(device.response0())))
        for (size in listOf(1, 32, 99, 480)) {
            val request = ByteArray(size) { (it * 13).toByte() }
            val encrypted = client.crypt(request)
            assertFalse(request.contentEquals(encrypted))
            assertArrayEquals(request, device.cipher.update(encrypted))
            val reply = ByteArray(size) { (it * 7).toByte() }
            assertArrayEquals(reply, client.crypt(device.cipher.update(reply)))
        }
        client.close()
        assertTrue(runCatching { client.crypt(byteArrayOf(1)) }.isFailure)
    }

    @Test
    fun noCodeSecurityOneStillEncryptsBothDirections() {
        val client = ProvisionSecurity()
        val device = Device(client, null)
        client.finish(device.response1(client.verify(device.response0())))
        val request = "Wi-Fi credentials".toByteArray()
        val encrypted = client.crypt(request)
        assertFalse(request.contentEquals(encrypted))
        assertArrayEquals(request, device.cipher.update(encrypted))
        assertArrayEquals(
            "ack".toByteArray(),
            client.crypt(device.cipher.update("ack".toByteArray())),
        )
    }

    @Test
    fun invalidProofDoesNotEstablishSession() {
        val client = ProvisionSecurity("wrong-code")
        val device = Device(client, pop)
        val challenge = client.verify(device.response0())
        val verify = Proto.field(Proto.field(Proto.field(challenge, 11), 22), 2)
        assertFalse(device.public.contentEquals(device.cipher.update(verify)))
        val fake =
            Proto.integer(2, 1) +
                Proto.bytes(
                    11,
                    Proto.integer(1, 3) + Proto.bytes(23, Proto.bytes(3, ByteArray(32))),
                )
        assertTrue(runCatching { client.finish(fake) }.isFailure)
        assertTrue(runCatching { client.crypt(byteArrayOf(1)) }.isFailure)
    }

    @Test
    fun downgradeTruncationAndDuplicateFieldsAreRejected() {
        val client = ProvisionSecurity(pop)
        client.hello()
        assertTrue(runCatching { client.verify(Proto.integer(2, 0)) }.isFailure)
        assertTrue(runCatching { Proto.field(byteArrayOf(0x5a, 0x7f, 1), 11) }.isFailure)
        assertTrue(
            runCatching { Proto.number(Proto.integer(2, 1) + Proto.integer(2, 1), 2) }.isFailure
        )
        assertTrue(runCatching { Proto.number(ByteArray(513), 2) }.isFailure)
        assertTrue(runCatching { ProvisionSecurity("").hello() }.isFailure)
    }
}
