package app.fusion.wallet.ui.treasury

import app.fusion.wallet.agent.AgentTickResult
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import org.json.JSONObject

/** Describe the actual selected action and its snapshot; never manufacture a deposit or rate. */
fun earnPreviewSummary(result: AgentTickResult, state: WalletState, txIndex: TxIndex = TxIndex.Empty): String {
    val report = JSONObject(result.reportJson)
    val candidate = report.getJSONObject("chosenCandidate")
    val reason = if (result.reason.equals("hold", true)) "nothing is worth moving right now" else readableCode(result.reason)
    if (result.choiceLabel == "H" || !result.previewPassed) return "The AI would hold: $reason" +
        if (!result.previewPassed) " · policy checks did not pass" else ""
    val action = candidate.getJSONObject("action")
    val amount = formatBigIntegerAmount(action.getString("assets").toBigInteger())
    fun vault(key: String): String = state.vaults.find { it.address.equals(action.optString(key), true) }?.name ?: "the verified vault"
    val description = when (candidate.getInt("kind")) {
        0 -> "The AI would move $amount USDC of spare cash into ${vault("destinationVault")}"
        1 -> "The AI would return $amount USDC from ${vault("sourceVault")} to Safe cash"
        2 -> "The AI would move $amount USDC from ${vault("sourceVault")} to ${vault("destinationVault")}"
        3 -> "The AI would pay $amount USDC to " + homeVendorLabel(state, txIndex, action.optString("recipient"))
        else -> "The AI would hold: action details unavailable"
    }
    val snapshot = report.getJSONObject("snapshot")
    val cash = snapshot.getString("safeCash").toBigInteger()
    val owed = snapshot.getJSONObject("moduleState").getString("reservedRaw").toBigInteger()
    val buffer = snapshot.getJSONObject("policy").getString("operatingBufferRaw").toBigInteger()
    val raw = action.getString("assets").toBigInteger()
    val covered = when (candidate.getInt("kind")) {
        0 -> cash - raw >= owed + buffer
        1 -> cash + raw >= owed + buffer
        2 -> cash >= owed + buffer
        3 -> raw <= owed && cash - raw >= owed - raw + buffer
        else -> false
    }
    val route = when (result.route.uppercase()) {
        "QUEUE" -> " · this move would be queued"
        "APPROVE" -> " · requires both Safe owners to approve"
        "APPROVE_AND_QUEUE" -> " · requires both Safe owners and the queue timelock"
        else -> ""
    }
    return description + (if (covered) " · bills stay covered" else "") + route
}


internal fun homeVendorLabel(state: WalletState, txIndex: TxIndex, address: String): String =
    txIndex.all().lastOrNull { it.recipient.equals(address, true) && !it.payeeLabel.isNullOrBlank() }?.payeeLabel
        ?: state.payees.find { it.address.equals(address, true) }?.label?.takeUnless { it.startsWith("Payee 0x") }
        ?: address
