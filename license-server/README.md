# Furniture IMS — licence server

This is the other half of milestone M14 (see `docs/04-roadmap.md`). It is a small,
dependency-free Cloudflare Worker that issues short-lived signed leases to activated
installations of the desktop app — see the comment at the top of `worker.js` for why a
*lease* rather than a plain "is this revoked?" boolean.

It is deliberately **not** part of the Maven build. Deploying it is an owner action, not
something the app or its CI pipeline does automatically.

## One-time setup

### 1. Generate the signing key pair

This key pair is the entire trust boundary: the private half signs every lease, the public
half is embedded in the Java app to verify them. Generate it **offline**, once:

```bash
openssl genpkey -algorithm ed25519 -out license-signing-key.pem
openssl pkey -in license-signing-key.pem -pubout -out license-signing-key.pub.pem
```

Then produce the two values each side actually needs:

```bash
# For the Worker secret (PKCS#8 private key, base64):
openssl pkey -in license-signing-key.pem -outform DER | base64 -w0

# For LicenseVerifier.PUBLIC_KEY_BASE64 (X.509 SubjectPublicKeyInfo, base64):
openssl pkey -in license-signing-key.pem -pubout -outform DER | base64 -w0
```

**Keep `license-signing-key.pem` somewhere durable and offline** (a password manager, an
encrypted USB drive) — if it is lost, no already-issued lease can ever be renewed, and every
activated shop eventually winds down. It must **never** be committed to this repository or
pasted anywhere public; only its public half is meant to be visible.

### 2. Create the KV namespace

```bash
cd license-server
wrangler kv namespace create LICENSES
```

Paste the printed `id` into `wrangler.toml`'s `kv_namespaces` entry.

### 3. Set the secrets

```bash
wrangler secret put LICENSE_SIGNING_KEY   # paste the base64 PKCS#8 value from step 1
wrangler secret put ADMIN_TOKEN           # any long random string - guards /admin/*
```

Optionally, for a Discord/Slack ping on every activation and every blocked second-PC
attempt, add to `wrangler.toml`:

```toml
[vars]
ALERT_WEBHOOK_URL = "https://discord.com/api/webhooks/..."
```

### 4. Deploy

```bash
wrangler deploy
```

Note the `*.workers.dev` URL it prints — that goes into `settings.license_server_url` in the
Java app (see `SettingsService`'s `DEFAULT_LICENSE_SERVER_URL`), and
`LicenseVerifier.PUBLIC_KEY_BASE64` needs the public key from step 1.

## Day-to-day operations

All `/admin/*` routes need `Authorization: Bearer <ADMIN_TOKEN>`.

**Issue a new activation key for a shop:**

```bash
curl -X POST https://<your-worker>.workers.dev/admin/issue \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"shopName": "Example Furniture Showroom"}'
```

Returns `{ "licenseId": "...", "activationKey": "FIMS-XXXX-XXXX-XXXX" }` — send the
`activationKey` to the shop; they type it into the setup wizard's activation step.

**See every licence, its bound machine, and every blocked attempt** (this is the "I will
know" half of the milestone):

```bash
curl https://<your-worker>.workers.dev/admin/activations \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The `blockedAttempts` array is the one to watch — a non-empty entry means someone tried to
run the software on a PC other than the one it was activated on.

**Revoke a licence** (the kill switch — the running copy winds down to read-only within one
renewal cycle, and fully within the 30-day lease window even if it never contacts the server
again):

```bash
curl -X POST https://<your-worker>.workers.dev/admin/revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"licenseId": "..."}'
```

**Reinstate a revoked licence:**

```bash
curl -X POST https://<your-worker>.workers.dev/admin/reinstate \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"licenseId": "..."}'
```

**Unbind a licence from its current PC** — needed when a shop legitimately replaces their
PC (a genuine hardware swap, not piracy). Clears the stored fingerprint so the next
`/activate` from the new PC succeeds instead of being blocked as a second machine:

```bash
curl -X POST https://<your-worker>.workers.dev/admin/unbind \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"licenseId": "..."}'
```

## Threat model

See `docs/04-roadmap.md`'s M14 section for the full table. In short: this stops a shop
copying the installer to a second PC or passing it on, and gives a real remote kill switch.
It cannot stop someone who clones this public repository, deletes the check, and rebuilds
from source — no client-side scheme can. It is sized for the realistic adversary (a shop
with an installer), not that one.
