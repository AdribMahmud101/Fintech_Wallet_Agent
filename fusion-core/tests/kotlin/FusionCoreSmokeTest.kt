import app.fusion.wallet.core.configureWallet
import app.fusion.wallet.core.executeIntent
import app.fusion.wallet.core.uniffiEnsureInitialized

// Anvil account #0 — publicly known test key, no funds on any live chain.
private const val TEST_KEY =
    "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

private var failures = 0

private fun check(name: String, condition: Boolean, detail: String) {
    if (condition) {
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name -> $detail")
    }
}

fun main() {
    uniffiEnsureInitialized()
    println("FusionCoreSmokeTest")

    println("[1] configureWallet")
    val configured = configureWallet(
        """{"rpc_url":"http://127.0.0.1:8545","chain_id":"1","whitelist":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48","max_tx_usd":"100","daily_limit_usd":"500"}"""
    )
    println("      $configured")
    check("configure succeeds", configured.contains("\"status\":\"success\""), configured)
    check("configure is refused twice", configureWallet("{}").contains("\"status\":\"failed\""), "re-configure was allowed")

    println("[2] guardrail rejections (no network involved)")
    val cases = listOf(
        "malformed json" to "{ not json",
        "unknown action" to """{"action":"swap","amount":1,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
        "unlisted token" to """{"action":"transfer","amount":1,"token_in":"DOGE","destination_address":"0x1111111111111111111111111111111111111111"}""",
        "missing destination" to """{"action":"transfer","amount":1,"token_in":"USDC"}""",
        "over tx size limit" to """{"action":"transfer","amount":500,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
        "amount_minor mismatch" to """{"action":"transfer","amount":1,"amount_minor":"1000000000","token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
    )
    for ((name, payload) in cases) {
        val result = executeIntent(payload, TEST_KEY)
        check(
            "blocks $name",
            result.contains("\"status\":\"blocked_by_guardrail\""),
            result,
        )
    }

    println("[3] bad credentials")
    val badKey = executeIntent(
        """{"action":"transfer","amount":1,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
        "0xnotavalidkey",
    )
    check("bad key fails, not blocks", badKey.contains("\"status\":\"failed\""), badKey)
    check("key never echoed", !badKey.contains("notavalidkey"), badKey)

    println("[4] real broadcast attempt against a dead RPC endpoint")
    val broadcast = executeIntent(
        """{"action":"transfer","amount":10,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
        TEST_KEY,
    )
    println("      ${broadcast.take(160)}")
    check("dead RPC reports failed", broadcast.contains("\"status\":\"failed\""), broadcast)
    check("no tx hash on failure", broadcast.contains("\"tx_hash\":null"), broadcast)

    println("[5] daily budget was not consumed by any failure above")
    // 5 x $10 must still fit under the $500 cap if failed transfers refunded correctly.
    repeat(50) {
        executeIntent(
            """{"action":"transfer","amount":10,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
            TEST_KEY,
        )
    }
    val after = executeIntent(
        """{"action":"transfer","amount":10,"token_in":"USDC","destination_address":"0x1111111111111111111111111111111111111111"}""",
        TEST_KEY,
    )
    check(
        "50 failed transfers never hit the daily cap",
        !after.contains("daily_limit_exceeded"),
        after,
    )

    println(if (failures == 0) "SMOKE TEST PASSED (0 failures)" else "SMOKE TEST FAILED ($failures)")
    if (failures > 0) kotlin.system.exitProcess(1)
}