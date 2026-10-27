package com.openrhodes.data

import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format from Espressif protocomm session.proto/sec1.proto, Security 1 only. Cryptographic
 * primitives are provided by Tink and the platform JCA provider. A single CTR stream is
 * deliberately consumed in request/response order.
 */
class ProvisionSecurity(private val proof: String? = null) {
    init {
        require(proof == null || proof.isNotBlank()) { "旧固件配网码为空，请更新固件" }
    }

    private val privateKey = X25519.generatePrivateKey()
    private val publicKey = X25519.publicFromPrivate(privateKey)
    private var cipher: Cipher? = null
    private var stage = 0

    fun hello(): ByteArray {
        check(stage == 0)
        stage = 1
        return envelope(Proto.bytes(20, Proto.bytes(1, publicKey)))
    }

    fun verify(response: ByteArray): ByteArray {
        check(stage == 1)
        val payload = responsePayload(response, 1, 21)
        require(Proto.number(payload, 1) == 0L) { "设备拒绝配网握手" }
        val peer = Proto.field(payload, 2)
        val iv = Proto.field(payload, 3)
        require(peer.size == 32 && iv.size == 16) { "配网握手格式无效" }
        val shared = X25519.computeSharedSecret(privateKey, peer)
        if (proof != null) {
            val digest =
                MessageDigest.getInstance("SHA-256").digest(proof.toByteArray(Charsets.UTF_8))
            for (i in shared.indices) shared[i] = (shared[i].toInt() xor digest[i].toInt()).toByte()
            digest.fill(0)
        }
        cipher =
            Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(shared, "AES"), IvParameterSpec(iv))
            }
        shared.fill(0)
        val challenge = requireNotNull(cipher).update(peer)
        stage = 2
        return envelope(Proto.integer(1, 2) + Proto.bytes(22, Proto.bytes(2, challenge)))
    }

    fun finish(response: ByteArray) {
        check(stage == 2)
        val payload = responsePayload(response, 3, 23)
        require(Proto.number(payload, 1) == 0L) { "设备拒绝配网验证" }
        val encrypted = Proto.field(payload, 3)
        require(encrypted.size == 32) { "配网验证格式无效" }
        val verified = requireNotNull(cipher).update(encrypted)
        require(MessageDigest.isEqual(publicKey, verified)) { "蓝牙加密验证失败，请重新连接" }
        privateKey.fill(0)
        stage = 3
    }

    fun crypt(value: ByteArray): ByteArray {
        check(stage == 3) { "蓝牙加密会话尚未建立" }
        return requireNotNull(cipher).update(value)
    }

    fun close() {
        privateKey.fill(0)
        cipher = null
        stage = -1
    }

    private fun envelope(payload: ByteArray) = Proto.integer(2, 1) + Proto.bytes(11, payload)

    private fun responsePayload(data: ByteArray, message: Long, field: Int): ByteArray {
        require(Proto.number(data, 2) == 1L) { "设备未使用加密配网协议" }
        val payload = Proto.field(data, 11)
        require(Proto.number(payload, 1) == message) { "配网握手顺序无效" }
        return Proto.field(payload, field)
    }
}

/** Small bounded protobuf wire reader: only varint and bytes are accepted. */
internal object Proto {
    private data class Item(val tag: Int, val number: Long?, val bytes: ByteArray?)

    private fun varint(value: Long): ByteArray {
        var v = value
        return ByteArrayOutputStream()
            .apply {
                do {
                    val b = (v and 127).toInt()
                    v = v ushr 7
                    write(b or if (v != 0L) 128 else 0)
                } while (v != 0L)
            }
            .toByteArray()
    }

    fun integer(tag: Int, value: Long) = varint((tag shl 3).toLong()) + varint(value)

    fun bytes(tag: Int, value: ByteArray) =
        varint(((tag shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    private fun parse(data: ByteArray): List<Item> {
        require(data.size <= 512) { "配网握手响应过长" }
        var offset = 0
        fun read(): Long {
            var result = 0L
            for (i in 0..8) {
                require(offset < data.size) { "配网握手被截断" }
                val b = data[offset++].toInt() and 255
                result = result or ((b and 127).toLong() shl (i * 7))
                if (b and 128 == 0) return result
            }
            error("配网握手整数无效")
        }
        return buildList {
            while (offset < data.size) {
                val key = read()
                val tag = (key ushr 3).toInt()
                require(tag > 0 && none { it.tag == tag }) { "配网握手字段无效" }
                when ((key and 7).toInt()) {
                    0 -> add(Item(tag, read(), null))
                    2 -> {
                        val n = read()
                        require(n in 0..(data.size - offset).toLong()) { "配网握手长度无效" }
                        add(Item(tag, null, data.copyOfRange(offset, offset + n.toInt())))
                        offset += n.toInt()
                    }
                    else -> error("配网握手字段类型无效")
                }
            }
        }
    }

    fun number(data: ByteArray, tag: Int): Long {
        val item = parse(data).firstOrNull { it.tag == tag } ?: return 0L
        return requireNotNull(item.number) { "配网握手字段类型无效" }
    }

    fun field(data: ByteArray, tag: Int): ByteArray =
        requireNotNull(parse(data).firstOrNull { it.tag == tag }?.bytes) { "配网握手缺少字段" }
}
