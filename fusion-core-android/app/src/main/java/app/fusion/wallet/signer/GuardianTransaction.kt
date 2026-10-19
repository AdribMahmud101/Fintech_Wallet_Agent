package app.fusion.wallet.signer

import java.math.BigInteger
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator

/** This encoder has exactly one destination, function, reason and chain. All amounts are wei. */
internal object GuardianTransaction {
    const val CHAIN_ID = 84532L
    const val MODULE = "0xA560ec10259657E9bADd297E68286d2838Cd5a8f"
    const val GUARDIAN = "0x46B8E40EB9767BD46E03C5eCF398C10E1372251E"
    const val RPC_URL = "https://sepolia.base.org"
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    val callData: String get() = "0xed56531a" + hex(keccak("APP_GUARDIAN_STOP".toByteArray())).drop(2)

    fun keccak(bytes: ByteArray): ByteArray = ByteArray(32).also {
        KeccakDigest(256).apply { update(bytes, 0, bytes.size); doFinal(it, 0) }
    }

    fun address(key: ByteArray): String {
        val point = curve.g.multiply(scalar(key)).normalize().getEncoded(false)
        return hex(keccak(point.copyOfRange(1, point.size)).takeLast(20).toByteArray())
    }

    private fun scalar(key: ByteArray): BigInteger {
        require(key.size == 32) { "Guardian key must be 32 bytes" }
        return BigInteger(1, key).also { require(it.signum() > 0 && it < curve.n) { "Invalid guardian key" } }
    }

    fun sign(key: ByteArray, nonce: BigInteger, tip: BigInteger, fee: BigInteger, gas: BigInteger): ByteArray {
        require(tip <= fee && gas.signum() > 0) { "Invalid guardian fees" }
        val fields = listOf(
            number(BigInteger.valueOf(CHAIN_ID)), number(nonce), number(tip), number(fee), number(gas),
            rlp(bytes(MODULE)), number(BigInteger.ZERO), rlp(bytes(callData)), list(emptyList()),
        )
        val digest = keccak(byteArrayOf(2) + list(fields))
        val d = scalar(key)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val signature = signer.generateSignature(digest)
        val r = signature[0]
        val s = signature[1].min(curve.n - signature[1]) // EIP-2 low s
        // R = (z + r*d)/s * G for the canonical signature; its y gives recovery parity.
        val recoveryPoint = curve.g.multiply(
            (BigInteger(1, digest) + r * d).multiply(s.modInverse(curve.n)).mod(curve.n),
        ).normalize()
        check(recoveryPoint.affineXCoord.toBigInteger() == r) { "Unsupported recovery point" }
        val parity = if (recoveryPoint.affineYCoord.toBigInteger().testBit(0)) BigInteger.ONE else BigInteger.ZERO
        return byteArrayOf(2) + list(fields + listOf(number(parity), number(r), number(s)))
    }

    private fun number(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 256) { "Invalid transaction quantity" }
        return rlp(if (value.signum() == 0) byteArrayOf() else value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    }
    private fun list(items: List<ByteArray>): ByteArray = prefix(items.fold(byteArrayOf()) { a, b -> a + b }, 192)
    private fun rlp(value: ByteArray): ByteArray =
        if (value.size == 1 && (value[0].toInt() and 255) < 128) value else prefix(value, 128)
    private fun prefix(value: ByteArray, offset: Int): ByteArray {
        if (value.size <= 55) return byteArrayOf((offset + value.size).toByte()) + value
        val length = BigInteger.valueOf(value.size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return byteArrayOf((offset + 55 + length.size).toByte()) + length + value
    }
    fun hex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun bytes(hex: String): ByteArray {
        val value = hex.removePrefix("0x")
        require(value.length % 2 == 0 && value.all { it.digitToIntOrNull(16) != null }) { "Invalid hex input" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
