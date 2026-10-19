package app.fusion.wallet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.fusion.wallet.R
import app.fusion.wallet.data.ActionKind
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.ui.shorten

/**
 * Human vendor names and plain-language action titles.
 *
 * The on-chain registry stores only a label *hash*, never the readable name, so the readable
 * names for the three demo vendors come from the K2 operational script
 * (`scripts/evidence_actions.py`: `keccak("fusion.k2.payee.<name>")`). Resolution is keyed by both
 * the payee id hash and the payee account address so a feed row that only knows the recipient
 * address still reads correctly. Anything unknown falls back to a shortened address — never a raw
 * hash and never a developer enum.
 */
enum class VendorKey { ACME, PAYROLL, NEWBANK }

object VendorCatalog {
    private val byHash = mapOf(
        "0xd54c2d42f79c30ee01aaf0f6e6572e858b25df57151e9a4cd5f22198a7de104f" to VendorKey.ACME,
        "0x445f4aa63bf907f8279287c2aab21d4b407ed9a801083a80ccc636f055e9e9dc" to VendorKey.PAYROLL,
        "0xb204a9d1b4378a80fd078f8e4ed3c6ec7950825b6e0d8a2cfee5c1809a509512" to VendorKey.NEWBANK,
    )
    private val byAddress = mapOf(
        "0x61dd06ce2f07d3e4f97d097741b0d7fd50f97f79" to VendorKey.ACME,
        "0x208bfee9f222d611b4db71d5ee53b3dab1a2188a" to VendorKey.PAYROLL,
        "0xc78be9da2204cc3020b055520102479fa3175066" to VendorKey.NEWBANK,
    )

    /** Known vendor for a payee id hash and/or account address, or null when unrecognised. */
    fun keyFor(idHash: String?, address: String?): VendorKey? {
        idHash?.lowercase()?.let { byHash[it]?.let { key -> return key } }
        address?.lowercase()?.let { byAddress[it]?.let { key -> return key } }
        return null
    }
}

@Composable
fun vendorKeyLabel(key: VendorKey): String = when (key) {
    VendorKey.ACME -> stringResource(R.string.vendor_acme)
    VendorKey.PAYROLL -> stringResource(R.string.vendor_payroll)
    VendorKey.NEWBANK -> stringResource(R.string.vendor_newbank)
}

/**
 * Display name for a vendor. Prefers the known readable name, otherwise "Vendor 0x61dd…7f79"
 * from whichever identifier we have. Never renders a bare hash.
 */
@Composable
fun vendorName(idHash: String?, address: String?): String {
    VendorCatalog.keyFor(idHash, address)?.let { return vendorKeyLabel(it) }
    val ref = address?.takeIf { it.isNotBlank() } ?: idHash
    return if (ref.isNullOrBlank()) stringResource(R.string.vendor_unknown)
    else stringResource(R.string.vendor_fallback, shorten(ref))
}

/**
 * Plain-language title for an executed or queued agent action. [vendor] is the already-resolved
 * display name (only used for payments); pass null when there is no counterparty.
 */
/** Readable permission-tier label instead of the raw enum name on the agent policy chip. */
@Composable
fun permissionTierLabel(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> stringResource(R.string.perm_read_only)
    PermissionTier.OWNER_APPROVED -> stringResource(R.string.perm_owner)
    PermissionTier.AUTONOMOUS -> stringResource(R.string.perm_autonomous)
}

/** Readable provenance label for who chose an action: Agent, Script, or App. Never a bare dot. */
@Composable
fun sourceChipLabel(source: String): String = when (source.uppercase()) {
    "AGENT" -> stringResource(R.string.chip_src_agent)
    "APP" -> stringResource(R.string.chip_src_app)
    else -> stringResource(R.string.chip_src_script)
}

@Composable
fun actionTitle(kind: ActionKind, vendor: String?, queued: Boolean = false): String = when (kind) {
    ActionKind.PAY_OBLIGATION ->
        if (queued) stringResource(R.string.act_pay_queued)
        else if (vendor != null) stringResource(R.string.act_pay_vendor, vendor)
        else stringResource(R.string.act_pay)
    ActionKind.DEPOSIT -> stringResource(R.string.act_deposit)
    ActionKind.WITHDRAW -> stringResource(R.string.act_withdraw)
    ActionKind.REBALANCE -> stringResource(R.string.act_rebalance)
}
