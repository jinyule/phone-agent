#!/usr/bin/env bash
# Builds and runs adb_vectors against BoringSSL (built in $BSSL/build) and AOSP android_pubkey.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
BSSL="${BSSL:?set BSSL to a BoringSSL checkout built with cmake into \$BSSL/build}"
AOSP="${AOSP:?set AOSP to a directory holding android_pubkey.cpp and crypto_utils/android_pubkey.h}"
out="${OUT:-$here/out}"
mkdir -p "$out"
CXX="${CXX:-clang++}"
flags=(-std=c++17 -O1 -I"$BSSL/include" -I"$AOSP")
"$CXX" "${flags[@]}" -DRAND_bytes=pa_vector_rand_bytes -c "$BSSL/crypto/curve25519/spake25519.cc" -o "$out/spake25519.o"
"$CXX" "${flags[@]}" -x c++ -c "$AOSP/android_pubkey.cpp" -o "$out/android_pubkey.o"
"$CXX" "${flags[@]}" -c "$here/adb_vectors.cc" -o "$out/adb_vectors.o"
"$CXX" "$out/adb_vectors.o" "$out/spake25519.o" "$out/android_pubkey.o" "$BSSL/build/libcrypto.a" -o "$out/adb_vectors"
"$out/adb_vectors" spake2 > "$out/spake2-vectors.txt"
"$out/adb_vectors" pubkey > "$out/adb-pubkey-vectors.txt"
nm "$out/adb_vectors" | grep -c pa_vector_rand_bytes
