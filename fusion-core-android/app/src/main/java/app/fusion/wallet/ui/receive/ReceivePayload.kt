package app.fusion.wallet.ui.receive

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** A plain address is interoperable with wallet scanners; network guidance stays next to it. */
fun receivePayload(safeAddress: String): String {
    require(Regex("0x[0-9a-fA-F]{40}").matches(safeAddress) && safeAddress.drop(2).any { it != '0' })
    return safeAddress
}
fun receiveQr(safeAddress: String) = QRCodeWriter().encode(receivePayload(safeAddress), BarcodeFormat.QR_CODE, 0, 0)
