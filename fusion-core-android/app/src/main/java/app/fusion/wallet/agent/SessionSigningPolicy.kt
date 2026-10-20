package app.fusion.wallet.agent

import org.json.JSONObject
import java.math.BigInteger

internal enum class SignerRefusal { NotModuleCall, NonzeroValue, WrongChain, UnsupportedSelector, MalformedCalldata }
internal class SignerRefused(val reason: SignerRefusal) : IllegalArgumentException("SignerRefused::${reason.name}")

/** Deployment authority comes from the bundled manifest, independently of the prepared tx. */
internal class SessionSigningPolicy(private val module: String, private val chainId: Long) {
    fun json() = JSONObject().put("module", module).put("chainId", chainId)

    fun validate(tx: JSONObject) {
        fun refuse(reason: SignerRefusal): Nothing = throw SignerRefused(reason)
        if (!Regex("0x[0-9a-fA-F]{40}").matches(module) || module.drop(2).all { it == '0' } ||
            !tx.optString("to").equals(module, true)) refuse(SignerRefusal.NotModuleCall)
        fun quantity(key: String): BigInteger = try {
            val value = tx.get(key).toString()
            if (value.startsWith("0x")) BigInteger(value.drop(2), 16) else BigInteger(value)
        } catch (_: Exception) { refuse(SignerRefusal.MalformedCalldata) }
        if (quantity("value") != BigInteger.ZERO) refuse(SignerRefusal.NonzeroValue)
        if (chainId <= 0 || quantity("chainId") != BigInteger.valueOf(chainId)) refuse(SignerRefusal.WrongChain)
        val data = tx.optString("data")
        if (!Regex("0x(?:[0-9a-fA-F]{2}){4,}").matches(data)) refuse(SignerRefusal.MalformedCalldata)
        if (data.take(10).lowercase() !in SessionEntryPoints.selectors) refuse(SignerRefusal.UnsupportedSelector)
        // Native signing independently ABI-decodes the full call and requires canonical encoding.
    }
}
