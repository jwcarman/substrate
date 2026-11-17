# Encryption at Rest

`substrate-crypto` encrypts every payload Substrate stores — atom values, journal entries and
mailbox values — with AES-GCM before it leaves the JVM. The backend only ever holds ciphertext.

```xml
<dependency>
    <groupId>org.jwcarman.substrate</groupId>
    <artifactId>substrate-crypto</artifactId>
</dependency>
```

Adding the module changes nothing on its own. Encryption starts when you give it a key, in one
of two ways.

## With one shared key

For a deployment that doesn't rotate keys, set one property:

```yaml
substrate:
  crypto:
    shared-key: ${SUBSTRATE_CRYPTO_KEY}
```

The value is a Base64-encoded AES key of 16 bytes (AES-128) or 32 bytes (AES-256). Any other
length stops the application at startup. Generate one with:

```bash
openssl rand -base64 32
```

On startup Substrate logs which mode is active:

```text
Substrate encryption-at-rest enabled: AES-256-GCM with shared key (kid=0)
```

Every node must have the same key. Keep it out of source control — an environment variable or
your secrets manager.

## With a key resolver, for rotation

To rotate keys, register a `SecretKeyResolver` bean instead. It answers two questions:

```java
public interface SecretKeyResolver {
  int currentKid();          // which key encrypts new data
  SecretKey lookup(int kid); // the key for a given ID, to decrypt existing data
}
```

```java
@Bean
SecretKeyResolver secretKeyResolver(KeyRing keys) {
  return new SecretKeyResolver() {
    @Override
    public int currentKid() {
      return keys.currentId();
    }

    @Override
    public SecretKey lookup(int kid) {
      return keys.get(kid); // cache: this runs on every read
    }
  };
}
```

`lookup` runs on every encryption and decryption, so it must be fast and thread-safe. Load keys
from your KMS or vault once and cache them; don't call a remote service per payload. Throw
`IllegalArgumentException` for an unknown key ID.

### Rotating

1. Add the new key to your key store, for example as key 8, and make sure every node can look it
   up.
2. Change `currentKid()` to return 8. New writes are encrypted with key 8.
3. Existing data still decrypts, because each payload records the key ID it was encrypted with and
   the resolver can still return key 7.
4. Retire key 7 only once nothing encrypted with it remains. Waiting for the longest TTL you use
   — at most your largest configured [ceiling](../concepts/index.md#upper-limits) — is enough,
   unless you append journal entries or complete journals with `Duration.ZERO`, which never
   expire.

!!! danger "A retired key makes its data unreadable"
    If `lookup` can no longer return a key that stored data was encrypted with, that data cannot
    be decrypted, ever. Reads of it fail.

Rolling out step 2 across a cluster takes time. Do step 1 everywhere first, so that every node
can decrypt data any other node encrypts with the new key.

## The wire format

Each stored payload is:

```text
[ key ID: 4 bytes | nonce: 12 bytes | ciphertext and 16-byte GCM tag ]
```

The nonce is random for every encryption. Shared-key mode uses key ID 0, or
`substrate.crypto.shared-kid`. Because the format is the same in both modes, you can move from a
shared key to a resolver later without re-encrypting anything: have the resolver return the old
key for ID 0.

## What is and isn't protected

Encrypted and authenticated: the payload — the value you pass to `set`, `append`, `deliver` and
so on. Tampering with a stored payload makes its decryption fail rather than return altered data.

Not encrypted:

- **Names and keys.** `orders:42` is stored, and sent in notifications, as plaintext. Don't put
  secrets in primitive names.
- **Metadata.** Staleness tokens, journal entry IDs and timestamps, TTLs and expiry times.

Each payload is encrypted on its own, without reference to the key it is stored under. Someone
able to write to your backend could copy a valid ciphertext from one atom to another, or replay an
older value, and it would decrypt. Encryption at rest protects against reading the backend, not
against writing to it; protect write access to the backend itself.

## Turning it on for existing data

Data written before encryption was enabled is plaintext, and it fails to decrypt afterwards.
Enable encryption on a fresh deployment, or let existing data expire first.

## Other algorithms

`substrate-crypto` ships AES-GCM only. For a different cipher, a FIPS provider, compression,
signing, or anything else, implement `PayloadTransformer` in `substrate-core` and register it as a
bean:

```java
public interface PayloadTransformer {
  byte[] encode(byte[] plaintext);
  byte[] decode(byte[] ciphertext);
}
```

Your bean replaces both the default pass-through and `substrate-crypto`'s transformer; you don't
need `substrate-crypto` for this.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `substrate.crypto.shared-key` | — | Base64 AES key, 16 or 32 bytes. Ignored when you register a `SecretKeyResolver`. |
| `substrate.crypto.shared-kid` | `0` | The key ID recorded with data encrypted under the shared key. |
