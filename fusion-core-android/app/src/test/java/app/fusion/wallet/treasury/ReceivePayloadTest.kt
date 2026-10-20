package app.fusion.wallet.treasury

import app.fusion.wallet.ui.receive.receivePayload
import app.fusion.wallet.ui.receive.receiveQr
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.common.BitMatrix
import org.junit.Assert.*
import org.junit.Test

class ReceivePayloadTest {
    private val safe = "0x029869910714D9e09b854a2688311AFd42fBb44E"
    @Test fun qrRoundTripsToExactSafeAddressOffline() {
        assertEquals(safe, receivePayload(safe))
        val qr = receiveQr(safe)
        // Writer uses four quiet modules per side; decoder expects the module matrix.
        val modules = BitMatrix(qr.width - 8)
        for (y in 0 until modules.height) for (x in 0 until modules.width) if (qr[x + 4, y + 4]) modules.set(x, y)
        assertEquals(safe, Decoder().decode(modules).text)
    }
    @Test fun rejectsInvalidOrZeroAddresses() {
        listOf("", "0x1234", "ethereum:$safe@1", "0x" + "0".repeat(40)).forEach {
            assertThrows(IllegalArgumentException::class.java) { receivePayload(it) }
        }
    }
}
