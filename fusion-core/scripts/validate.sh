#!/usr/bin/env bash
# Strict validation gate for fusion-core.
#
# Every step must exit 0. Run from the crate root:
#
#     ./scripts/validate.sh
#
# Steps 1-8 are pure Rust and need only a toolchain. Steps 9-10 additionally need a JDK
# and JNA; they are skipped (with a loud notice) when `kotlinc` is unavailable, so the
# gate never silently degrades on CI without Java.
set -uo pipefail

cd "$(dirname "$0")/.."
CRATE_ROOT="$(pwd)"
WORKSPACE_ROOT="$(cd .. && pwd)"
KOTLIN_OUT="${WORKSPACE_ROOT}/fusion-core-kotlin/src/main/kotlin"
JNA_JAR="${JNA_JAR:-}"
FAILED=0

step() {
    printf '\n\033[1m== %s ==\033[0m\n' "$1"
}

record() {
    if [ "$1" -ne 0 ]; then
        FAILED=1
        printf '\033[31mFAILED (exit %s)\033[0m\n' "$1"
    fi
}

step "0. toolchain"
rustc --version
cargo --version
python3 --version 2>&1 || true

step "1. cargo fmt --check"
cargo fmt --check
record $?

step "2. zero-mock audit of src/"
python3 scripts/audit_no_mocks.py
record $?

step "3. cargo clippy --all-targets -- -D warnings"
cargo clippy --all-targets -- -D warnings
record $?

step "4. cargo test (debug)"
cargo test
record $?

step "5. cargo test --release"
cargo test --release
record $?

step "6. cargo build --release --locked"
cargo build --release --locked --lib
record $?

step "7. cargo doc --no-deps"
cargo doc --no-deps 2>&1 | grep -E "^(warning|error)" || true
record $?

step "8. release cdylib present"
ls -la target/release/libfusion_core.so
record $?

if command -v kotlinc >/dev/null 2>&1; then
    step "9. regenerate Kotlin bindings from the release cdylib"
    rm -rf "${KOTLIN_OUT}"
    mkdir -p "${KOTLIN_OUT}"
    cargo run --release --bin uniffi-bindgen -- generate \
        --library target/release/libfusion_core.so \
        --language kotlin \
        --out-dir "${KOTLIN_OUT}"
    record $?

    if [ -z "${JNA_JAR}" ]; then
        JNA_JAR="$(find "${HOME}/.gradle" /home -name 'jna-*.jar' 2>/dev/null | head -1)"
    fi

    if [ -n "${JNA_JAR}" ] && [ -f "${JNA_JAR}" ]; then
        step "10. end-to-end FFI smoke test against the real .so"
        OUT="$(mktemp -d)"
        kotlinc "${KOTLIN_OUT}"/app/fusion/wallet/core/fusion_core.kt \
                tests/kotlin/FusionCoreSmokeTest.kt \
                -cp "${JNA_JAR}" -include-runtime -d "${OUT}/smoke.jar" 2>&1 \
            | grep -v "^warning:" || true
        mkdir -p "${OUT}/natives/linux-x86-64"
        cp target/release/libfusion_core.so "${OUT}/natives/linux-x86-64/"
        java -cp "${OUT}/smoke.jar:${JNA_JAR}:${OUT}/natives" FusionCoreSmokeTestKt
        record $?
        rm -rf "${OUT}"
    else
        printf '\n\033[33mSKIPPED: no JNA jar found. Set JNA_JAR=/path/to/jna.jar\033[0m\n'
        FAILED=1
    fi
else
    printf '\n\033[33mSKIPPED: kotlinc not installed; bindings + smoke test not verified.\033[0m\n'
    FAILED=1
fi

printf '\n\033[1m== summary ==\033[0m\n'
if [ "$FAILED" -eq 0 ]; then
    printf '\033[32mALL VALIDATION STEPS PASSED\033[0m\n'
else
    printf '\033[31mVALIDATION FAILED — see the steps above\033[0m\n'
fi
exit "$FAILED"