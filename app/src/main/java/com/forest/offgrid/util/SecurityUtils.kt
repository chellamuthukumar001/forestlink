package com.forest.offgrid.util

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    // Matches the key used in ESP32 firmware
    private const val PRE_SHARED_SECRET = "FORESTLINK2026!!"
    private const val ALGORITHM = "AES/ECB/PKCS5Padding"

    private fun generateKey(): SecretKeySpec {
        // Simple 16-byte key from the secret
        val keyBytes = PRE_SHARED_SECRET.toByteArray(Charsets.UTF_8).copyOf(16)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef".toCharArray()
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun hexToBytes(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) +
                    Character.digit(hexString[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun encrypt(plainText: String): String {
        return try {
            val key = generateKey()
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            bytesToHex(encryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            plainText 
        }
    }

    fun decrypt(encryptedText: String): String {
        return try {
            // If the text is not valid AES hex blocks (multiple of 32 hex chars), treat as plain text
            if (encryptedText.length < 32 || encryptedText.length % 32 != 0 || !encryptedText.all { it in "0123456789abcdefABCDEF" }) {
                return encryptedText
            }
            val key = generateKey()
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decodedBytes = hexToBytes(encryptedText)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val result = String(decryptedBytes, Charsets.UTF_8).filter { it.code in 32..126 }
            if (result.isNotBlank()) result else encryptedText
        } catch (e: Exception) {
            e.printStackTrace()
            encryptedText
        }
    }

    fun encryptBytes(data: ByteArray): ByteArray {
        val key = generateKey()
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    fun decryptBytes(data: ByteArray): ByteArray {
        val key = generateKey()
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(data)
    }
}

