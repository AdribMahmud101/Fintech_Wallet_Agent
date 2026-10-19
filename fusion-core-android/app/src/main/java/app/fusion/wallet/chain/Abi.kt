package app.fusion.wallet.chain

import java.math.BigInteger

/** Strict ABI words for the deployed V4.1 view surface; offsets are byte offsets. */
class Abi(encoded: String) {
    private val hex = encoded.removePrefix("0x")
    val size: Int get() = hex.length / 64
    init {
        require(encoded.startsWith("0x") && hex.length % 64 == 0 && hex.all { it.digitToIntOrNull(16) != null }) {
            "Malformed ABI response"
        }
    }
    fun requireSize(words: Int): Abi = apply { require(size == words) { "Expected $words ABI words, got $size" } }
    fun uint(index: Int): BigInteger = BigInteger(word(index), 16)
    fun long(index: Int): Long = uint(index).longValueExact()
    fun int(index: Int): Int = uint(index).intValueExact()
    fun bool(index: Int): Boolean = when (uint(index)) {
        BigInteger.ZERO -> false
        BigInteger.ONE -> true
        else -> error("Invalid ABI boolean")
    }
    fun bytes32(index: Int): String = "0x" + word(index).lowercase()
    fun address(index: Int): String {
        val value = word(index)
        require(value.take(24).all { it == '0' }) { "Invalid ABI address padding" }
        return "0x" + value.takeLast(40).lowercase()
    }
    fun offset(index: Int): Int {
        val bytes = int(index)
        require(bytes % 32 == 0 && bytes / 32 < size) { "Invalid ABI offset" }
        return bytes / 32
    }
    private fun word(index: Int): String {
        require(index >= 0 && index < size) { "Truncated ABI response at word $index" }
        return hex.substring(index * 64, (index + 1) * 64)
    }
    companion object {
        fun uintWord(value: BigInteger): String {
            require(value.signum() >= 0 && value.bitLength() <= 256)
            return value.toString(16).padStart(64, '0')
        }
        fun addressWord(address: String): String {
            require(Regex("0x[0-9a-fA-F]{40}").matches(address))
            return address.drop(2).lowercase().padStart(64, '0')
        }
        fun hashWord(hash: String): String {
            require(Regex("0x[0-9a-fA-F]{64}").matches(hash))
            return hash.drop(2).lowercase()
        }
    }
}

object Views {
    const val BALANCE = "0x70a08231"
    const val CONVERT_TO_ASSETS = "0x07a2d13a"
    const val MAX_WITHDRAW = "0xce96cb77"
    const val PREVIEW_DEPOSIT = "0xef8b30f7"
    const val PREVIEW_WITHDRAW = "0x0a28a477"
    const val STATE = "0x1865c57d"
    const val POLICY = "0xce1e4626"
    const val QUEUE = "0x01fce27e"
    const val PAYEE = "0x00ca2f75"
    const val SANCTIONED = "0xdf592f7d"
    const val PAID = "0xfeef6640"
}
