#!/usr/bin/env python3
"""Read-only Base Sepolia probe for K32; uses AgentGasReader's pinned RPC calls.

From the umbrella worktree:
python3 src/Fintech_Wallet_Agent/scripts/reproduce_agent_gas.py --tx-index evidence/tx_index.json

The Android failure was local: JsonRpcClient's READ_METHODS omitted eth_getBalance
and eth_getTransactionReceipt. This probe confirms the session, quantity, batch
result shape and snapshot hash independently of that allowlist.
"""

import argparse
import json
import re
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tx-index", type=Path, help="Optional evidence/tx_index.json")
    parser.add_argument("--capture", type=Path, help="Save raw RPC requests/responses")
    args = parser.parse_args()
    captures = []
    next_id = 0

    def envelope(method, params):
        nonlocal next_id
        next_id += 1
        return dict(jsonrpc="2.0", id=next_id, method=method, params=params)

    def send(payload):
        # Keep diagnostic batches below the public RPC's 25 requests/second cap.
        time.sleep(1.1)
        request = urllib.request.Request(
            "https://sepolia.base.org", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "User-Agent": "FusionWallet-K32/1.0"},
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            result = json.load(response)
        captures.append(dict(request=payload, response=result))
        return result

    def result(response, request):
        assert response["jsonrpc"] == "2.0" and response["id"] == request["id"], response
        assert "error" not in response and response.get("result") is not None, response
        return response["result"]

    def rpc(method, params):
        request = envelope(method, params)
        return result(send(request), request)

    def batch(calls):
        if not calls:
            return []
        requests = [envelope(method, params) for method, params in calls]
        responses = send(requests)
        assert isinstance(responses, list), responses
        by_id = {response["id"]: response for response in responses}
        return [result(by_id[request["id"]], request) for request in requests]

    block = rpc("eth_getBlockByNumber", ["latest", False])
    tag = block["number"]
    state = rpc("eth_call", [{"to": "0xA560ec10259657E9bADd297E68286d2838Cd5a8f",
                              "data": "0x1865c57d"}, tag])
    assert re.fullmatch(r"0x[0-9a-fA-F]{1088}", state), state  # 17 ABI words
    session = "0x" + state[2 + 13 * 64 + 24:2 + 14 * 64]
    assert re.fullmatch(r"0x[0-9a-fA-F]{40}", session) and int(session, 16) != 0
    balance = rpc("eth_getBalance", [session, tag])
    assert isinstance(balance, str) and re.fullmatch(r"0x[0-9a-fA-F]+", balance), balance
    entries = json.loads(args.tx_index.read_text()) if args.tx_index else []
    receipts = []
    for start in range(0, len(entries), 20):
        chunk = entries[start:start + 20]
        found = batch([("eth_getTransactionReceipt", [entry["txHash"]]) for entry in chunk])
        for entry, receipt in zip(chunk, found):
            assert receipt["transactionHash"].lower() == entry["txHash"].lower()
            if receipt["from"].lower() == session.lower() and int(receipt["blockNumber"], 16) <= int(tag, 16):
                receipts.append(receipt)
    receipts.sort(key=lambda r: int(r["blockNumber"], 16), reverse=True)
    receipts = receipts[:20]
    heights = list(dict.fromkeys(r["blockNumber"] for r in receipts))
    headers = dict(zip(heights, batch([("eth_getBlockByNumber", [height, False]) for height in heights])))
    costs = []
    for receipt in receipts:
        header = headers[receipt["blockNumber"]]
        assert header["number"] == receipt["blockNumber"]
        if header["hash"].lower() != receipt["blockHash"].lower():
            continue
        if not int(block["timestamp"], 16) - 30 * 86400 <= int(header["timestamp"], 16) <= int(block["timestamp"], 16):
            continue
        costs.append(int(receipt["gasUsed"], 16) * int(receipt["effectiveGasPrice"], 16)
                     + sum(int(receipt.get(key) or "0x0", 16) for key in ("l1Fee", "operatorFee")))
    assert rpc("eth_getBlockByNumber", [tag, False])["hash"].lower() == block["hash"].lower()
    print(json.dumps(dict(block=tag, blockHash=block["hash"], session=session,
                          balanceWei=str(int(balance, 16)), actualFeeSamplesWei=[str(cost) for cost in costs]), indent=2))
    if args.capture:
        args.capture.write_text(json.dumps(captures, indent=2) + "\n")


if __name__ == "__main__":
    main()
