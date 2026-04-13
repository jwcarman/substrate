# Add substrate-crypto module with AES-GCM PayloadTransformer

## What to build

A new optional Maven module `substrate-crypto` providing an
`AesGcmPayloadTransformer` keyed by a `SecretKeyResolver` abstraction,
plus Spring Boot autoconfig so users can opt in via a single property
or a custom key-lookup bean.

Spec 066 must be complete first — this spec depends on the
`PayloadTransformer` extension point.

### Module layout

```
substrate-crypto/
├── pom.xml                                            (depends on substrate-core + spring-boot)
└── src/main/java/org/jwcarman/substrate/crypto/
    ├── SecretKeyResolver.java                         (SPI: lookup by kid + currentKid)
    ├── AesGcmPayloadTransformer.java                  (the one transformer this module ships)
    ├── SubstrateCryptoProperties.java                 (@ConfigurationProperties)
    └── SubstrateCryptoAutoConfiguration.java          (@AutoConfiguration)
```

### `SecretKeyResolver`

```java
// substrate-crypto
package org.jwcarman.substrate.crypto;

public interface SecretKeyResolver {

  /**
   * The key ID to use for new encryptions. Called once per encode(). Implementations that never
   * rotate may return a fixed value.
   */
  int currentKid();

  /**
   * Look up a key by its ID. Called on both the encode path (via {@link #currentKid()}) and the
   * decode path (for any kid embedded in existing ciphertext). Implementations MUST be able to
   * resolve every kid that appears in stored data — retiring a kid makes all blobs encrypted
   * under that kid permanently unreadable.
   *
   * @throws IllegalArgumentException if the kid is unknown
   */
  SecretKey lookup(int kid);

  /**
   * Convenience: returns a resolver that serves a single key at a fixed kid (defaults to 0).
   * Useful for simple deployments that don't need rotation.
   */
  static SecretKeyResolver shared(SecretKey key) {
    return shared(key, 0);
  }

  static SecretKeyResolver shared(SecretKey key, int kid) {
    return new SecretKeyResolver() {
      @Override public int currentKid() { return kid; }
      @Override public SecretKey lookup(int k) {
        if (k != kid) {
          throw new IllegalArgumentException(
              "Unknown kid " + k + "; shared resolver only serves kid " + kid);
        }
        return key;
      }
    };
  }
}
```

### `AesGcmPayloadTransformer`

```java
package org.jwcarman.substrate.crypto;

public class AesGcmPayloadTransformer implements PayloadTransformer {

  private static final String  ALGORITHM = "AES/GCM/NoPadding";
  private static final int     NONCE_LEN = 12;
  private static final int     TAG_BITS  = 128;
  private static final int     KID_LEN   = 4;

  private final SecretKeyResolver resolver;
  private final SecureRandom rng = new SecureRandom();

  public AesGcmPayloadTransformer(SecretKeyResolver resolver) {
    this.resolver = resolver;
  }

  // Wire format: [kid(4) | nonce(12) | ciphertext + GCM tag]

  @Override
  public byte[] encode(byte[] plaintext) {
    int kid = resolver.currentKid();
    SecretKey key = resolver.lookup(kid);
    byte[] nonce = new byte[NONCE_LEN];
    rng.nextBytes(nonce);
    try {
      Cipher c = Cipher.getInstance(ALGORITHM);
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = c.doFinal(plaintext);
      ByteBuffer buf = ByteBuffer.allocate(KID_LEN + NONCE_LEN + ct.length);
      buf.putInt(kid).put(nonce).put(ct);
      return buf.array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM encrypt failed", e);
    }
  }

  @Override
  public byte[] decode(byte[] ciphertext) {
    if (ciphertext.length < KID_LEN + NONCE_LEN) {
      throw new IllegalArgumentException(
          "Ciphertext too short to contain kid + nonce: " + ciphertext.length + " bytes");
    }
    ByteBuffer buf = ByteBuffer.wrap(ciphertext);
    int kid = buf.getInt();
    SecretKey key = resolver.lookup(kid);
    byte[] nonce = new byte[NONCE_LEN];
    buf.get(nonce);
    try {
      Cipher c = Cipher.getInstance(ALGORITHM);
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return c.doFinal(ciphertext, buf.position(), buf.remaining());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM decrypt failed for kid " + kid, e);
    }
  }
}
```

### Properties

```java
@ConfigurationProperties(prefix = "substrate.crypto")
public record SubstrateCryptoProperties(
    String sharedKey,    // base64-encoded AES key (16 or 32 bytes raw) — optional
    Integer sharedKid    // kid assigned to the shared key (optional, defaults to 0)
) {
  public SubstrateCryptoProperties {
    if (sharedKid == null) sharedKid = 0;
  }
}
```

The `sharedKey` property is the convenience entry point for users who
don't need key rotation. Users who do need rotation provide their own
`@Bean SecretKeyResolver` (pulling keys from KMS, Vault, a keystore,
whatever) and leave `sharedKey` unset.

### Autoconfig

```java
@AutoConfiguration
@EnableConfigurationProperties(SubstrateCryptoProperties.class)
public class SubstrateCryptoAutoConfiguration {

  /**
   * Shared-key convenience: if the user sets substrate.crypto.shared-key and hasn't registered
   * their own SecretKeyResolver bean, decode the property into a SecretKey and wrap it in a
   * fixed-kid resolver.
   */
  @Bean
  @ConditionalOnProperty("substrate.crypto.shared-key")
  @ConditionalOnMissingBean(SecretKeyResolver.class)
  public SecretKeyResolver sharedKeyResolver(SubstrateCryptoProperties props) {
    byte[] raw = Base64.getDecoder().decode(props.sharedKey());
    validateAesKeyLength(raw.length);
    return SecretKeyResolver.shared(new SecretKeySpec(raw, "AES"), props.sharedKid());
  }

  /**
   * Wire the AES-GCM transformer whenever a SecretKeyResolver is in the context, UNLESS the user
   * has already provided their own PayloadTransformer (they win).
   */
  @Bean
  @ConditionalOnBean(SecretKeyResolver.class)
  @ConditionalOnMissingBean(PayloadTransformer.class)
  public PayloadTransformer aesGcmPayloadTransformer(SecretKeyResolver resolver) {
    return new AesGcmPayloadTransformer(resolver);
  }

  private static void validateAesKeyLength(int bytes) {
    if (bytes != 16 && bytes != 32) {
      throw new IllegalStateException(
          "substrate.crypto.shared-key must decode to 16 bytes (AES-128) or 32 bytes (AES-256); "
              + "got " + bytes + " bytes");
    }
  }
}
```

### Activation matrix

| `substrate.crypto.shared-key` set? | User `SecretKeyResolver` bean? | User `PayloadTransformer` bean? | Effective transformer |
|---|---|---|---|
| — | — | — | `PayloadTransformer.IDENTITY` (from substrate-core) |
| ✓ | — | — | `AesGcmPayloadTransformer` with shared resolver at kid 0 |
| ✓ | ✓ | — | **User's resolver wins** (their bean outranks the autoconfig resolver). Autoconfig's `AesGcmPayloadTransformer` still gets created, using the user's resolver. |
| — | ✓ | — | User's resolver, autoconfig's `AesGcmPayloadTransformer` |
| — or ✓ | — or ✓ | ✓ | User's transformer wins; autoconfig does not create anything |

Documenting this matrix clearly in the module's javadoc.

### pom.xml

- New module in the parent reactor.
- `groupId=org.jwcarman.substrate, artifactId=substrate-crypto`.
- Depends on:
  - `substrate-core` (for `PayloadTransformer`)
  - Spring Boot autoconfiguration / context (same way other substrate-*
    modules do it)
  - Standard test dependencies
- Gets added to `substrate-bom` so users picking it up via BOM get the
  version-aligned artifact.

### META-INF/spring.factories / AutoConfiguration.imports

Add the `SubstrateCryptoAutoConfiguration` class name to
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
so Spring Boot picks it up automatically when `substrate-crypto` is
on the classpath.

### Documentation

- Module README explaining:
  - Quick start: set `substrate.crypto.shared-key=<base64>` and you're
    encrypted.
  - Production: implement your own `SecretKeyResolver` backed by a KMS
    / Vault / keystore, cache aggressively because `lookup()` is
    called on the read hot path.
  - Key rotation story: bump `currentKid()` on your resolver, new
    writes use the new key, old reads still work because the resolver
    can serve historical kids. **Retiring a kid makes all blobs
    encrypted under it unreadable** — treat kid retirement as "lose
    the data."
  - Key size: 16 bytes → AES-128, 32 bytes → AES-256. Anything else
    fails fast at startup.
- Add a `substrate-crypto` section to the top-level README pointing
  at the module.
- Add a CHANGELOG entry under `[Unreleased]` for the 0.4.0 release.

## Acceptance criteria

- [ ] New `substrate-crypto/` Maven module exists with the four source files above.
- [ ] `SecretKeyResolver` is a public interface with `int currentKid()`, `SecretKey lookup(int kid)`, and the two `shared(...)` static factory methods.
- [ ] `AesGcmPayloadTransformer` uses the `[kid(4) | nonce(12) | ciphertext+tag]` wire format via `AES/GCM/NoPadding` from the JDK. No third-party crypto libraries (no BouncyCastle, no Tink).
- [ ] `SubstrateCryptoProperties` is a `@ConfigurationProperties` record at prefix `substrate.crypto` with `sharedKey` (String, base64) and `sharedKid` (Integer, default 0).
- [ ] `SubstrateCryptoAutoConfiguration` wires the shared-key resolver (`@ConditionalOnProperty("substrate.crypto.shared-key")` + `@ConditionalOnMissingBean(SecretKeyResolver.class)`) and the transformer (`@ConditionalOnBean(SecretKeyResolver.class)` + `@ConditionalOnMissingBean(PayloadTransformer.class)`).
- [ ] Autoconfig registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- [ ] Module added to the root `pom.xml`'s `<modules>` list and to `substrate-bom`'s dependency management.
- [ ] Tests cover:
  - `SecretKeyResolver.shared(key)` returns the key for kid=0, throws `IllegalArgumentException` for any other kid.
  - `AesGcmPayloadTransformer.encode` → `.decode` round-trips a variety of payload sizes (empty, 1 byte, 1 KB, 10 KB).
  - Encoding the same plaintext twice produces different ciphertext (nonce randomness).
  - Decoding ciphertext produced with a kid the resolver doesn't know throws `IllegalStateException` wrapping the `IllegalArgumentException` from the resolver.
  - Decoding truncated ciphertext (less than kid+nonce bytes) throws `IllegalArgumentException` with a clear message.
  - Decoding ciphertext that's been tampered with (e.g. flip a byte in the tag region) throws `IllegalStateException` from GCM's authentication check.
  - `SubstrateCryptoAutoConfigurationTest` verifies the activation matrix: no property + no resolver → no transformer bean; shared-key only → shared resolver + AES-GCM transformer; user resolver + no shared-key → AES-GCM transformer wrapping user resolver; user transformer → user transformer wins.
  - Rotation round-trip test: encode with kid=7, flip `currentKid()` to 8, encode again, confirm both blobs decode (resolver serves both 7 and 8).
  - `validateAesKeyLength` rejects non-16/32-byte keys with a clear startup-time error message.
- [ ] `./mvnw verify` passes from root (including the new substrate-crypto module).
- [ ] `./mvnw -P release javadoc:jar -DskipTests` passes for substrate-crypto — all public types have doc comments, no doclint errors.
- [ ] README / CHANGELOG updated.

## Implementation notes

- Use `java.util.Base64.getDecoder()` for the shared-key property — standard Base64, not URL-safe. That's the convention for secrets in environment variables and config files (matches what most KMS tools output).
- `SubstrateCryptoAutoConfiguration` should be `@AutoConfigureAfter(SubstrateAutoConfiguration.class)` so the identity `PayloadTransformer.IDENTITY` bean from substrate-core is already considered when our `@ConditionalOnMissingBean(PayloadTransformer.class)` is evaluated. Without the ordering hint, the autoconfig firing order could cause both to register or neither to register depending on classpath scan order.
- Actually — on that: the substrate-core bean is also `@ConditionalOnMissingBean`. So if substrate-crypto's autoconfig fires first and registers `AesGcmPayloadTransformer`, substrate-core's `IDENTITY` bean sees the transformer is already present and backs off. Either ordering works. But explicit `@AutoConfigureAfter` documents intent.
- The `@ConditionalOnProperty("substrate.crypto.shared-key")` on the shared resolver bean means the property must be non-empty. Users who leave it blank get the same behavior as omitting it entirely.
- Don't cache `Cipher` instances — they're not thread-safe. Each call creates a fresh one. The overhead is minimal and the correctness guarantee is worth it.
- Don't cache `SecretKey` lookups in the transformer itself — that's the resolver's job. If the resolver has expensive lookups (KMS calls), it should cache internally. Keep the transformer dumb and stateless.
- `AesGcmPayloadTransformer.encode` wraps `GeneralSecurityException` in `IllegalStateException` because (a) failed encryption with a valid key should be essentially impossible — if it happens, something is catastrophically wrong (unchecked is correct), and (b) `PayloadTransformer.encode`'s contract doesn't throw checked exceptions (it just returns `byte[]`).

## Out of scope

- Non-AES algorithms (ChaCha20-Poly1305, AES-GCM-SIV, etc.). Users who need a different algorithm implement their own `PayloadTransformer` and register it as a bean — substrate-crypto is intentionally opinionated.
- Key management itself (key creation, rotation scheduling, storage, access control). That's KMS / Vault / HSM territory; substrate-crypto just consumes keys via the resolver.
- Key versioning / migration tooling (e.g., re-encrypting existing ciphertext with a new key). Users with a migration need write their own pass; the `SecretKeyResolver` design intentionally supports reading under the old key indefinitely so migration can happen at the user's pace.
- Per-primitive transformer selection ("encrypt atoms but not journals"). Same `PayloadTransformer` applies to all primitives. A user wanting selective transformation writes a transformer that routes internally.
- Compression. Could be a separate hypothetical `substrate-compression` module using the same `PayloadTransformer` hook, but not part of this spec.
