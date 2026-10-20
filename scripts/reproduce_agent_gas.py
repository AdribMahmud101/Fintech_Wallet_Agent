#!/usr/bin/env python3
"""Read-only reproduction of the app's index -> receipt -> header -> fee path.

Default: fixed repository (refresh GitHub index, fall back to --tx-index cache).
--mode k32: cached index only, old 50-call receipt batches and old RPC budget.
--warm-cache: simulate rememberTxIndex having refreshed the K32 disk cache first.
--tx-index: seed the app cache from a file (never modifies the supplied file).

Examples from the umbrella worktree:
  python3 src/Fintech_Wallet_Agent/scripts/reproduce_agent_gas.py --mode k32 --warm-cache
  python3 src/Fintech_Wallet_Agent/scripts/reproduce_agent_gas.py
Use --capture to retain every raw HTTP request/response, including batch errors.
"""

import argparse
from collections import Counter, deque
from decimal import Decimal
import hashlib
import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

INDEX_URL = "https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/tx_index.json"
RPC_URL = "https://sepolia.base.org"


def quantity(value):
    assert isinstance(value, str) and re.fullmatch(r"0x[0-9a-fA-F]+", value), value
    return int(value, 16)


def parse_index(raw):
    # TxIndex.parse validates the whole document and lowercases keys before caching receipts.
    rows = json.loads(raw)
    assert isinstance(rows, list)
    entries = {}
    for row in rows:
        tx = row["txHash"]
        assert re.fullmatch(r"0x[0-9a-fA-F]{64}", tx)
        assert row["source"] in {"SCRIPT", "OWNER", "AGENT", "APP"}
        assert isinstance(row["kind"], str) and isinstance(row["evidenceFile"], str)
        if row.get("amountRaw") not in (None, ""):
            assert re.fullmatch(r"[0-9]+", row["amountRaw"])
        if row.get("recipient") not in (None, ""):
            assert re.fullmatch(r"0x[0-9a-fA-F]{40}", row["recipient"])
        assert tx.lower() not in entries
        entries[tx.lower()] = dict(row, txHash=tx.lower())
    return list(entries.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["k32", "fixed"], default="fixed")
    parser.add_argument("--tx-index", type=Path, help="Seed disk cache; missing/invalid means empty")
    parser.add_argument("--warm-cache", action="store_true", help="Simulate UI refreshing the K32 cache")
    parser.add_argument("--capture", type=Path)
    args = parser.parse_args()
    captures, batches, reservations = [], [], deque()
    next_id, next_slot = 0, 0.0
    dropped = Counter()

    try:
        cached_raw = args.tx_index.read_text() if args.tx_index else "[]"
        entries = parse_index(cached_raw)
        origin = str(args.tx_index) if args.tx_index else "missing disk cache"
    except (OSError, ValueError, AssertionError, KeyError):
        entries, cached_raw, origin = [], "[]", "missing/invalid disk cache"
    cached_count = len(entries)
    if args.mode == "fixed" or args.warm_cache:
        try:
            request = urllib.request.Request(INDEX_URL, headers={"User-Agent": "FusionWallet-Android"})
            with urllib.request.urlopen(request, timeout=4) as response:
                raw = response.read().decode()
            assert len(raw) <= 2_000_000
            entries = parse_index(raw)
            cached_raw, origin = raw, INDEX_URL
        except (OSError, ValueError, AssertionError, KeyError) as error:
            origin += f" (refresh failed: {error})"

    def acquire(calls):
        nonlocal next_slot
        now = time.monotonic()
        if args.mode == "k32":
            # RpcReadBudget originally reserved time AFTER the burst; calls >25 always fail.
            start = max(now, next_slot)
            next_slot = start + calls * 0.05
        else:
            assert 1 <= calls <= 20
            start = max(now, reservations[-1][0] if reservations else now)
            while True:
                while reservations and reservations[0][0] <= start - 1:
                    reservations.popleft()
                if sum(count for _, count in reservations) + calls <= 20:
                    break
                start = reservations[0][0] + 1
            reservations.append((start, calls))
        if start > now:
            time.sleep(start - now)

    def envelope(method, params):
        nonlocal next_id
        next_id += 1
        return dict(jsonrpc="2.0", id=next_id, method=method, params=params)

    def send(payload):
        acquire(len(payload) if isinstance(payload, list) else 1)
        request = urllib.request.Request(
            RPC_URL, data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "User-Agent": "FusionWallet-Android"},
        )
        try:
            with urllib.request.urlopen(request, timeout=6) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            body = error.read().decode()
            captures.append(dict(request=payload, httpStatus=error.code, response=body))
            raise RuntimeError(f"HTTP {error.code}: {body[:512]}") from error
        captures.append(dict(request=payload, response=result))
        if isinstance(payload, list):
            errors = [r["error"] for r in result if "error" in r] if isinstance(result, list) else [result]
            batches.append(dict(method=payload[0]["method"], size=len(payload), errors=errors))
        return result

    def result(response, request):
        assert response["id"] == request["id"], response
        if "error" in response:
            raise RuntimeError(str(response["error"]))
        assert response.get("result") is not None, response
        return response["result"]

    def retry(read):
        for attempt in range(3):
            try:
                return read()
            except RuntimeError as error:
                if attempt == 2 or not re.search(r"rate|request limit|HTTP 429", str(error), re.I):
                    raise
                time.sleep(0.5 * 2 ** attempt)

    def rpc(method, params):
        def once():
            request = envelope(method, params)
            response = send(request)
            assert response["jsonrpc"] == "2.0", response
            return result(response, request)
        return retry(once)

    def batch(calls):
        def once(chunk):
            requests = [envelope(method, params) for method, params in chunk]
            responses = send(requests)
            assert isinstance(responses, list), responses
            by_id = {response["id"]: response for response in responses}
            # JsonRpcClient rejects the ENTIRE batch on any error or null result.
            assert set(by_id) == {request["id"] for request in requests}
            return [result(by_id[request["id"]], request) for request in requests]
        size = 20 if args.mode == "fixed" else max(1, len(calls))
        return [value for start in range(0, len(calls), size)
                for value in retry(lambda: once(calls[start:start + size]))]

    block = rpc("eth_getBlockByNumber", ["latest", False])
    tag = block["number"]
    state = rpc("eth_call", [{"to": "0xA560ec10259657E9bADd297E68286d2838Cd5a8f",
                              "data": "0x1865c57d"}, tag])
    assert re.fullmatch(r"0x[0-9a-fA-F]{1088}", state), state  # 17 ABI words
    session = "0x" + state[2 + 13 * 64 + 24:2 + 14 * 64]
    assert re.fullmatch(r"0x[0-9a-fA-F]{40}", session) and int(session, 16) != 0
    balance = quantity(rpc("eth_getBalance", [session, tag]))
    receipts = {}
    for start in range(0, len(entries), 50):  # AgentGasReader chunk size, including K32
        chunk = entries[start:start + 50]
        try:
            found = batch([("eth_getTransactionReceipt", [entry["txHash"]]) for entry in chunk])
            assert len(found) == len(chunk)
            receipts.update((entry["txHash"], value) for entry, value in zip(chunk, found) if isinstance(value, dict))
        except (RuntimeError, AssertionError, OSError, KeyError) as error:
            dropped["receiptBatchFailure"] += len(chunk)
            dropped[f"receiptBatchError: {error}"] += 1
    candidates = []
    for entry in entries:
        receipt = receipts.get(entry["txHash"])
        if receipt is None:
            continue
        try:
            if receipt["transactionHash"].lower() != entry["txHash"]:
                dropped["transactionHash"] += 1
            elif receipt["from"].lower() != session.lower():
                dropped["fromSession"] += 1
            elif quantity(receipt["blockNumber"]) > quantity(tag):
                dropped["futureBlock"] += 1
            else:
                candidates.append(receipt)
        except (AssertionError, KeyError, TypeError, ValueError):
            dropped["malformedReceipt"] += 1
    candidates.sort(key=lambda r: quantity(r["blockNumber"]), reverse=True)
    candidates = candidates[:20]
    heights = list(dict.fromkeys(r["blockNumber"] for r in candidates))
    try:
        headers = dict(zip(heights, batch([("eth_getBlockByNumber", [height, False]) for height in heights])))
        assert all(quantity(headers[h]["number"]) == quantity(h) for h in heights)
    except (RuntimeError, AssertionError, OSError, KeyError) as error:
        headers = {}
        dropped[f"headerBatchError: {error}"] += 1
    costs, timestamps = [], []
    for receipt in candidates:
        try:
            header = headers.get(receipt["blockNumber"])
            if header is None:
                dropped["missingHeader"] += 1
            elif header["hash"].lower() != receipt["blockHash"].lower():
                receipts.pop(receipt["transactionHash"].lower(), None)
                dropped["blockHash"] += 1
            elif not quantity(block["timestamp"]) - 30 * 86400 <= quantity(header["timestamp"]) <= quantity(block["timestamp"]):
                dropped["outside30Days"] += 1
            else:
                costs.append(quantity(receipt["gasUsed"]) * quantity(receipt["effectiveGasPrice"])
                             + sum(quantity(receipt[key]) if receipt.get(key) is not None else 0
                                   for key in ("l1Fee", "operatorFee")))
                timestamps.append(quantity(header["timestamp"]))
        except (AssertionError, KeyError, TypeError, ValueError):
            dropped["malformedFeeOrHeader"] += 1
    assert rpc("eth_getBlockByNumber", [tag, False])["hash"].lower() == block["hash"].lower()
    positive_costs = sorted(cost for cost in costs if cost > 0)
    n = len(positive_costs)
    median = (Decimal(positive_costs[(n - 1) // 2]) + Decimal(positive_costs[n // 2])) / 2 if n else None
    print(json.dumps(dict(
        mode=args.mode, indexOrigin=origin, cachedEntries=cached_count, indexEntries=len(entries),
        indexSha256=hashlib.sha256(cached_raw.encode()).hexdigest(),
        indexSources=dict(Counter(e["source"] for e in entries)), block=tag, blockHash=block["hash"],
        blockTimestamp=quantity(block["timestamp"]), session=session, balanceWei=str(balance),
        cachedReceipts=len(receipts), sessionCandidates=len(candidates), headers=len(headers),
        batches=batches, dropped=dict(dropped), sampleTimestamps=timestamps,
        actualFeeSamplesWei=[str(cost) for cost in costs], sampleCount=len(costs),
        medianCostWei=str(median) if median else None,
        estimatedMoves=str(int(Decimal(balance) // median)) if median else None,
    ), indent=2))
    if args.capture:
        args.capture.write_text(json.dumps(captures, indent=2) + "\n")


if __name__ == "__main__":
    main()
