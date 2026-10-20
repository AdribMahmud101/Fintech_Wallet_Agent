package app.fusion.wallet.ui.owner

import app.fusion.wallet.chain.Abi
import org.bouncycastle.crypto.digests.KeccakDigest
import java.math.BigInteger

/** Unsigned ERC-20 transfer payload for the Safe owners to review and sign. */
data class OwnerTransaction(val to: String, val value: String, val data: String, val operation: String = "CALL") {
    fun json(): String = "{\"to\":\"$to\",\"value\":\"$value\",\"data\":\"$data\",\"operation\":\"$operation\"}"
}

object OwnerTransactionEncoder {
    fun checksummedAddress(input: String): String? {
        if (!Regex("0x[0-9a-fA-F]{40}").matches(input)) return null
        val lower = input.drop(2).lowercase()
        val digest = ByteArray(32)
        val bytes = lower.toByteArray(Charsets.US_ASCII)
        KeccakDigest(256).apply { update(bytes, 0, bytes.size); doFinal(digest, 0) }
        val hash = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        val checksum = buildString(40) {
            lower.forEachIndexed { index, char -> append(if (hash[index].digitToInt(16) >= 8) char.uppercaseChar() else char) }
        }.let { "0x$it" }
        return checksum.takeIf { input == it }
    }

    fun transfer(token: String, recipient: String, amountRaw: BigInteger): OwnerTransaction {
        require(Regex("0x[0-9a-fA-F]{40}").matches(token)) { "Invalid token address" }
        require(checksummedAddress(recipient) != null) { "Recipient must be a valid checksummed address" }
        require(amountRaw.signum() > 0 && amountRaw.bitLength() <= 256) { "Amount must be positive" }
        return OwnerTransaction(
            to = token,
            value = "0",
            data = "0xa9059cbb" + Abi.addressWord(recipient) + Abi.uintWord(amountRaw),
        )
    }
}

fun parseUsdcAmount(value: String): BigInteger? {
    if (!Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?").matches(value)) return null
    val parts = value.split('.')
    return runCatching {
        BigInteger(parts[0]).multiply(BigInteger.TEN.pow(6)) +
            BigInteger((parts.getOrElse(1) { "" }).padEnd(6, '0').ifEmpty { "0" })
    }.getOrNull()?.takeIf { it.signum() > 0 && it.bitLength() <= 256 }
}
