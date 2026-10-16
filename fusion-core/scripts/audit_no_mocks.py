#!/usr/bin/env python3
"""Enforce the "zero mock logic in src/" rule.

Strips every `#[cfg(test)]` item from `src/**/*.rs` and then fails if what remains
contains mock transports, stub macros, placeholder markers or hardcoded hashes.

Run from the crate root:

    python3 scripts/audit_no_mocks.py
"""

import pathlib
import re
import sys

FORBIDDEN = [
    "todo!",
    "unimplemented!",
    "panic!(",
    "FIXME",
    "XXX",
    "dummy",
    "DUMMY",
    "placeholder",
    "Asserter",
    "MockTransport",
    "mocked_client",
    "stub",
    "fake",
    "not implemented",
    "for now",
]

# The two mainnet ERC-20 addresses are *configuration data*, not test fixtures, and are
# pinned to their byte arrays by `config::tests::builtin_asset_literals_agree_with_their_bytes`.
ADDRESS_ALLOWLIST = {
    "src/config.rs": {
        "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",  # USDC
        "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",  # WETH
    },
}

HEX_HASH = re.compile(r"0x[0-9a-fA-F]{16,}")


def strip_test_items(text: str) -> str:
    """Remove every `#[cfg(test)]`-annotated item, including whole modules."""
    out, i = [], 0
    while True:
        m = re.search(r"#\[cfg\(test\)\]", text[i:])
        if not m:
            out.append(text[i:])
            break
        start = i + m.start()
        out.append(text[i:start])
        brace = text.find("{", start)
        if brace == -1:
            out.append(text[start:])
            break
        depth, k = 0, brace
        while k < len(text):
            if text[k] == "{":
                depth += 1
            elif text[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        i = k + 1
    return "".join(out)


def code_lines(text: str):
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("#["):
            continue
        yield line


def main() -> int:
    problems: list[str] = []

    for path in sorted(pathlib.Path("src").rglob("*.rs")):
        key = path.as_posix()
        production = strip_test_items(path.read_text())
        allowed = ADDRESS_ALLOWLIST.get(key, set())

        for lineno, line in enumerate(code_lines(production), 1):
            for needle in FORBIDDEN:
                if needle in line:
                    problems.append(f"{key}:{lineno} forbidden token {needle!r}: {line.strip()}")

            for literal in HEX_HASH.findall(line):
                if literal not in allowed:
                    problems.append(f"{key}:{lineno} hardcoded hash {literal}: {line.strip()}")

    if problems:
        print("AUDIT FAILED — src/ contains mock/stub logic outside #[cfg(test)]:")
        for problem in problems:
            print(f"  {problem}")
        return 1

    print("AUDIT PASSED — src/ (excluding #[cfg(test)]) contains no mock transports, "
          "stub macros, placeholder markers or hardcoded hashes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())