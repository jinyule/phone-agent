// Known-answer vectors for PhoneAgent's adb pairing code (core.adb), computed with the code that
// runs on devices: BoringSSL's SPAKE2 (crypto/curve25519/spake25519.cc), HKDF and AES-128-GCM AEAD,
// used the way AOSP's adb pairing_auth does (packages/modules/adb/pairing_auth), and AOSP's
// android_pubkey_encode (system/core/libcrypto_utils) for the adb public key format.
//
// spake25519.cc is compiled with -DRAND_bytes=pa_vector_rand_bytes so each SPAKE2 private seed is
// a value printed here; every other input comes from a fixed SHA-512 counter stream, so the SPAKE2
// part is reproducible. The RSA keys come from BoringSSL's real RNG (the file records them).
#include <openssl/aead.h>
#include <openssl/base64.h>
#include <openssl/bn.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/hkdf.h>
#include <openssl/rsa.h>
#include <openssl/sha.h>

#include <crypto_utils/android_pubkey.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using Bytes = std::vector<uint8_t>;

static Bytes g_next_seed;

extern "C" int pa_vector_rand_bytes(uint8_t *out, size_t len) {
  if (g_next_seed.size() != len) abort();
  memcpy(out, g_next_seed.data(), len);
  g_next_seed.clear();
  return 1;
}

// Deterministic byte stream: SHA-512("phone-agent adb vectors" || counter_le64) blocks.
static uint64_t g_counter = 0;
static Bytes stream(size_t len) {
  Bytes out;
  while (out.size() < len) {
    uint8_t block[64];
    SHA512_CTX sha;
    SHA512_Init(&sha);
    static const char kLabel[] = "phone-agent adb vectors";
    SHA512_Update(&sha, kLabel, sizeof(kLabel) - 1);
    uint8_t c[8];
    for (int i = 0; i < 8; i++) c[i] = (uint8_t)(g_counter >> (8 * i));
    g_counter++;
    SHA512_Update(&sha, c, 8);
    SHA512_Final(block, &sha);
    out.insert(out.end(), block, block + 64);
  }
  out.resize(len);
  return out;
}

static std::string hex(const Bytes &b) {
  static const char *d = "0123456789abcdef";
  std::string s;
  for (uint8_t x : b) {
    s += d[x >> 4];
    s += d[x & 15];
  }
  return s;
}

static const uint8_t kClientName[] = "adb pair client";  // sizeof includes the NUL, as in AOSP
static const uint8_t kServerName[] = "adb pair server";

struct Party {
  Bytes msg, key;
};

static Party spake(spake2_role_t role, const Bytes &password, const Bytes &seed, const Bytes *their_msg,
                   SPAKE2_CTX **keep) {
  const bool alice = role == spake2_role_alice;
  SPAKE2_CTX *ctx = SPAKE2_CTX_new(role, alice ? kClientName : kServerName, sizeof(kClientName),
                                   alice ? kServerName : kClientName, sizeof(kServerName));
  uint8_t msg[SPAKE2_MAX_MSG_SIZE];
  size_t msg_len = 0;
  g_next_seed = seed;
  if (!SPAKE2_generate_msg(ctx, msg, &msg_len, sizeof(msg), password.data(), password.size())) abort();
  Party p;
  p.msg.assign(msg, msg + msg_len);
  if (their_msg) {
    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t key_len = 0;
    if (!SPAKE2_process_msg(ctx, key, &key_len, sizeof(key), their_msg->data(), their_msg->size())) abort();
    p.key.assign(key, key + key_len);
    SPAKE2_CTX_free(ctx);
  } else {
    *keep = ctx;
  }
  return p;
}

static Bytes process(SPAKE2_CTX *ctx, const Bytes &their_msg) {
  uint8_t key[SPAKE2_MAX_KEY_SIZE];
  size_t key_len = 0;
  if (!SPAKE2_process_msg(ctx, key, &key_len, sizeof(key), their_msg.data(), their_msg.size())) abort();
  SPAKE2_CTX_free(ctx);
  return Bytes(key, key + key_len);
}

// AOSP Aes128Gcm: HKDF-SHA256(key material, no salt, info without NUL) -> 16-byte key; the nonce is
// the 64-bit sequence number in host (little-endian) order followed by zeros.
static Bytes aes_key(const Bytes &key_material) {
  uint8_t key[16];
  static const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
  if (!HKDF(key, sizeof(key), EVP_sha256(), key_material.data(), key_material.size(), nullptr, 0, info,
            sizeof(info) - 1))
    abort();
  return Bytes(key, key + 16);
}

static Bytes seal(const Bytes &key, uint64_t seq, const Bytes &plain) {
  EVP_AEAD_CTX ctx;
  if (!EVP_AEAD_CTX_init(&ctx, EVP_aead_aes_128_gcm(), key.data(), key.size(), EVP_AEAD_DEFAULT_TAG_LENGTH,
                         nullptr))
    abort();
  uint8_t nonce[12] = {0};
  memcpy(nonce, &seq, sizeof(seq));
  Bytes out(plain.size() + EVP_AEAD_max_overhead(EVP_aead_aes_128_gcm()));
  size_t out_len = 0;
  if (!EVP_AEAD_CTX_seal(&ctx, out.data(), &out_len, out.size(), nonce, sizeof(nonce), plain.data(), plain.size(),
                         nullptr, 0))
    abort();
  out.resize(out_len);
  EVP_AEAD_CTX_cleanup(&ctx);
  return out;
}

// Low three bits of SHA-512(password) mod L: which of BoringSSL's cofactor corrections apply.
static int scalar_pattern(const Bytes &password) {
  uint8_t h[64];
  SHA512(password.data(), password.size(), h);
  BIGNUM *n = BN_le2bn(h, 64, nullptr);
  BIGNUM *l = nullptr;
  BN_hex2bn(&l, "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed");
  BN_CTX *bc = BN_CTX_new();
  BN_mod(n, n, l, bc);
  uint8_t le[32];
  BN_bn2le_padded(le, sizeof(le), n);
  int pattern = le[0] & 7;
  BN_free(n);
  BN_free(l);
  BN_CTX_free(bc);
  return pattern;
}

static Bytes code_password(const std::string &code, const Bytes &exported) {
  Bytes p(code.begin(), code.end());
  p.insert(p.end(), exported.begin(), exported.end());
  return p;
}

static void emit_case(const char *note, const Bytes &alice_pw, const Bytes &bob_pw, size_t plain_len) {
  Bytes alice_seed = stream(64), bob_seed = stream(64);
  SPAKE2_CTX *alice_ctx = nullptr;
  Party alice = spake(spake2_role_alice, alice_pw, alice_seed, nullptr, &alice_ctx);
  Party bob = spake(spake2_role_bob, bob_pw, bob_seed, &alice.msg, nullptr);
  alice.key = process(alice_ctx, bob.msg);
  Bytes alice_aes = aes_key(alice.key), bob_aes = aes_key(bob.key);
  Bytes plain = stream(plain_len);
  printf("# %s\n", note);
  printf("alice_pw=%s\n", hex(alice_pw).c_str());
  printf("bob_pw=%s\n", hex(bob_pw).c_str());
  printf("alice_seed=%s\n", hex(alice_seed).c_str());
  printf("bob_seed=%s\n", hex(bob_seed).c_str());
  printf("alice_msg=%s\n", hex(alice.msg).c_str());
  printf("bob_msg=%s\n", hex(bob.msg).c_str());
  printf("alice_shared=%s\n", hex(alice.key).c_str());
  printf("bob_shared=%s\n", hex(bob.key).c_str());
  printf("alice_aes=%s\n", hex(alice_aes).c_str());
  printf("bob_aes=%s\n", hex(bob_aes).c_str());
  printf("plaintext=%s\n", hex(plain).c_str());
  printf("alice_sealed0=%s\n", hex(seal(alice_aes, 0, plain)).c_str());
  printf("alice_sealed1=%s\n", hex(seal(alice_aes, 1, plain)).c_str());
  printf("bob_sealed0=%s\n", hex(seal(bob_aes, 0, plain)).c_str());
  printf("\n");
}

static void emit_pubkey() {
  RSA *rsa = RSA_new();
  BIGNUM *e = BN_new();
  BN_set_word(e, RSA_F4);
  if (!RSA_generate_key_ex(rsa, 2048, e, nullptr)) abort();
  uint8_t buf[ANDROID_PUBKEY_ENCODED_SIZE];
  if (!android_pubkey_encode(rsa, buf, sizeof(buf))) abort();
  uint8_t n_be[256];
  BN_bn2bin_padded(n_be, sizeof(n_be), RSA_get0_n(rsa));
  size_t b64_len = 0;
  EVP_EncodedLength(&b64_len, sizeof(buf));
  std::string b64(b64_len, '\0');
  EVP_EncodeBlock(reinterpret_cast<uint8_t *>(&b64[0]), buf, sizeof(buf));
  b64.resize(strlen(b64.c_str()));
  printf("modulus=%s\n", hex(Bytes(n_be, n_be + 256)).c_str());
  printf("exponent=%llu\n", (unsigned long long)BN_get_word(RSA_get0_e(rsa)));
  printf("encoded=%s\n\n", b64.c_str());
  BN_free(e);
  RSA_free(rsa);
}

int main(int argc, char **argv) {
  const std::string which = argc > 1 ? argv[1] : "spake2";
  if (which == "pubkey") {
    printf("# adb RSA public keys (RSA-2048, e = 65537) encoded by AOSP android_pubkey_encode, base64.\n\n");
    emit_pubkey();
    emit_pubkey();
    return 0;
  }
  printf("# SPAKE2 (BoringSSL spake25519) + HKDF + AES-128-GCM, as used by AOSP adb pairing_auth.\n");
  printf("# alice = client (\"adb pair client\\0\"), bob = server (\"adb pair server\\0\").\n");
  printf("# *_seed: the 64 bytes RAND_bytes returned to SPAKE2_generate_msg (before the mod-L reduction).\n");
  printf("# *_sealed<n>: AEAD seal under that side's AES-128-GCM key with sequence number n (nonce = seq LE64 || 0^32).\n\n");
  // Matching passwords: a 6-digit code followed by 64 bytes of TLS exported keying material, until
  // every low-3-bit pattern of the password scalar (each combination of BoringSSL's cofactor
  // corrections) has one case.
  bool seen[8] = {false};
  int found = 0;
  while (found < 8) {
    Bytes digits = stream(6);
    std::string code;
    for (uint8_t d : digits) code += (char)('0' + d % 10);
    Bytes pw = code_password(code, stream(64));
    int pattern = scalar_pattern(pw);
    if (seen[pattern]) continue;
    seen[pattern] = true;
    found++;
    char note[96];
    snprintf(note, sizeof(note), "same code %s; password scalar mod 8 = %d", code.c_str(), pattern);
    emit_case(note, pw, pw, pattern == 0 ? 8192 : 33);
  }
  Bytes exported = stream(64);
  emit_case("different codes 123456 / 123457, same exported key", code_password("123456", exported),
            code_password("123457", exported), 33);
  emit_case("same code 000000, exported keys differ in the last byte",
            code_password("000000", exported),
            [&] {
              Bytes p = code_password("000000", exported);
              p.back() ^= 1;
              return p;
            }(),
            33);
  return 0;
}
