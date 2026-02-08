package com.riskr.app

import java.util.Arrays

object Base58 {
    private val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray()
    private val ENCODED_ZERO = ALPHABET[0]
    private val INDEXES = IntArray(128)

    init {
        Arrays.fill(INDEXES, -1)
        for (i in ALPHABET.indices) {
            INDEXES[ALPHABET[i].code] = i
        }
    }

    // --- 编码 (ByteArray -> String) ---
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var inputCopy = Arrays.copyOf(input, input.size)

        var zeros = 0
        while (zeros < inputCopy.size && inputCopy[zeros].toInt() == 0) {
            ++zeros
        }

        val encoded = CharArray(inputCopy.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < inputCopy.size) {
            val decoded = encoded
            decoded[--outputStart] = ALPHABET[divmod(inputCopy, inputStart, 256, 58).toInt()]
            if (inputCopy[inputStart].toInt() == 0) {
                ++inputStart
            }
        }

        while (outputStart < encoded.size && encoded[outputStart] == ENCODED_ZERO) {
            ++outputStart
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ENCODED_ZERO
        }

        return String(encoded, outputStart, encoded.size - outputStart)
    }

    // --- 解码 (String -> ByteArray) 【这里彻底修复了】 ---
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        // 1. 将字符串转换为 58 进制的数字数组
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            if (digit < 0) throw IllegalArgumentException("Illegal character $c at position $i")
            input58[i] = digit.toByte()
        }

        // 2. 统计前导零
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) {
            ++zeros
        }

        // 3. 转换为 256 进制 (Bytes)
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros

        while (inputStart < input58.size) {
            // 核心修复：使用 divmod 进行进制转换，而不是之前的错误逻辑
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256).toByte()
            if (input58[inputStart].toInt() == 0) {
                ++inputStart
            }
        }

        // 4. 去除多余的前导零
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) {
            ++outputStart
        }

        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.size)
    }

    // --- 核心辅助函数：大数除法 ---
    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder.toByte()
    }

    fun decodeAddress(addr: String): ByteArray = decode(addr)
}