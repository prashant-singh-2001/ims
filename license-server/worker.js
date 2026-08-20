/**
 * Furniture IMS licence server (milestone M14).
 *
 * Issues short-lived, Ed25519-signed leases to activated installations. The app never asks
 * this service "am I revoked?" - a boolean answer would be trivial to spoof or to block with
 * a hosts-file entry. Instead it asks for a *lease*, and winds itself down when the lease it
 * holds goes stale. That collapses three separate problems into one mechanism:
 *
 *   - revocation        -> we stop issuing, the held lease ages out
 *   - endpoint blocking -> they cannot reach us, the held lease ages out
 *   - offline tolerance -> the lease is valid for LEASE_DAYS with no contact at all
 *
 * Deliberately dependency-free: Ed25519 signing goes through WebCrypto, which Workers
 * implement natively, so there is no npm install, no lockfile and no supply chain here.
 *
 * Bindings required (see wrangler.toml):
 *   KV  LICENSES              the only datastore
 *   SEC LICENSE_SIGNING_KEY   base64 PKCS#8 Ed25519 private key - NEVER commit this
 *   SEC ADMIN_TOKEN           bearer token guarding /admin/*
 *   VAR ALERT_WEBHOOK_URL     optional; Discord/Slack webhook for activation alerts
 */

const LEASE_DAYS = 30;

// ---- KV layout ---------------------------------------------------------------------------
// key:<activationKey>  -> licenceId            (lookup index, so a shop can type a short code)
// lic:<licenceId>      -> the licence record   (the actual state)

const keyIndex = (activationKey) => `key:${activationKey.trim().toUpperCase()}`;
const licRecord = (licenceId) => `lic:${licenceId}`;

// ---- base64url ---------------------------------------------------------------------------

function b64uEncode(bytes) {
  let binary = '';
  for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64Decode(base64) {
  const binary = atob(base64.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

// ---- signing -----------------------------------------------------------------------------

let cachedSigningKey = null;

async function signingKey(env) {
  if (!cachedSigningKey) {
    if (!env.LICENSE_SIGNING_KEY) throw new Error('LICENSE_SIGNING_KEY secret is not set');
    cachedSigningKey = await crypto.subtle.importKey(
      'pkcs8', b64Decode(env.LICENSE_SIGNING_KEY), { name: 'Ed25519' }, false, ['sign'],
    );
  }
  return cachedSigningKey;
}

/**
 * Produces the compact `base64url(payload).base64url(signature)` token the Java client
 * verifies. Deliberately JWT-shaped but NOT a JWT: there is no header, so there is no
 * "alg" field an attacker could set to "none". The algorithm is fixed on both sides.
 */
async function mintLease(env, licence, licenceId) {
  const now = new Date();
  const expires = new Date(now.getTime() + LEASE_DAYS * 86_400_000);
  const payload = {
    licenseId: licenceId,
    shopName: licence.shopName ?? null,
    fp: licence.fingerprint,
    issuedAt: now.toISOString(),
    expiresAt: expires.toISOString(),
    status: 'ACTIVE',
  };
  const payloadBytes = new TextEncoder().encode(JSON.stringify(payload));
  const signature = await crypto.subtle.sign('Ed25519', await signingKey(env), payloadBytes);
  return { token: `${b64uEncode(payloadBytes)}.${b64uEncode(signature)}`, expiresAt: payload.expiresAt };
}

// ---- helpers -----------------------------------------------------------------------------

const json = (body, status = 200) =>
  new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

function authorised(request, env) {
  const header = request.headers.get('authorization') ?? '';
  return Boolean(env.ADMIN_TOKEN) && header === `Bearer ${env.ADMIN_TOKEN}`;
}

/** Best-effort notification. Never allowed to fail a licence operation. */
async function alert(env, text) {
  if (!env.ALERT_WEBHOOK_URL) return;
  try {
    await fetch(env.ALERT_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ content: text, text }),
    });
  } catch (e) {
    console.error('alert webhook failed', e);
  }
}

const newId = () => crypto.randomUUID();

/** Human-typeable, unambiguous alphabet: no O/0, no I/1. */
function newActivationKey() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const random = crypto.getRandomValues(new Uint8Array(12));
  const chars = [...random].map((byte) => alphabet[byte % alphabet.length]);
  return `FIMS-${chars.slice(0, 4).join('')}-${chars.slice(4, 8).join('')}-${chars.slice(8, 12).join('')}`;
}

// ---- routes ------------------------------------------------------------------------------

/**
 * First contact from a fresh install. Binds the activation key to whichever machine gets
 * here first; every later machine presenting the same key is refused and recorded.
 *
 * The 409 branch is the entire point of this milestone's "I will know" half.
 */
async function activate(request, env) {
  const body = await readJson(request);
  if (!body?.activationKey || !body?.fingerprint) {
    return json({ error: 'activationKey and fingerprint are required' }, 400);
  }

  const licenceId = await env.LICENSES.get(keyIndex(body.activationKey));
  if (!licenceId) {
    await alert(env, `Unknown activation key attempted: ${body.activationKey} (machine ${body.machineName ?? '?'})`);
    return json({ error: 'That activation key was not recognised.' }, 404);
  }

  const licence = await env.LICENSES.get(licRecord(licenceId), 'json');
  if (!licence) return json({ error: 'Licence record missing.' }, 500);

  if (licence.status === 'REVOKED') {
    return json({ error: 'This licence has been revoked. Please contact the supplier.' }, 403);
  }

  if (licence.fingerprint && licence.fingerprint !== body.fingerprint) {
    // A second PC is trying to use a key already bound elsewhere.
    licence.rejectedAttempts = (licence.rejectedAttempts ?? []).slice(-49);
    licence.rejectedAttempts.push({
      at: new Date().toISOString(),
      fingerprint: body.fingerprint,
      machineName: body.machineName ?? null,
      volumeSerial: body.volumeSerial ?? null,
      appVersion: body.appVersion ?? null,
    });
    await env.LICENSES.put(licRecord(licenceId), JSON.stringify(licence));
    await alert(env,
      `⚠️ New PC blocked for "${licence.shopName ?? licenceId}" - key ${body.activationKey} is already bound. `
      + `Machine "${body.machineName ?? '?'}", fingerprint ${body.fingerprint.slice(0, 16)}...`);
    return json({
      error: 'This activation key is already in use on another PC. Contact the supplier to move your licence.',
    }, 409);
  }

  const firstActivation = !licence.fingerprint;
  licence.fingerprint = body.fingerprint;
  licence.volumeSerial = body.volumeSerial ?? null;
  licence.machineName = body.machineName ?? null;
  licence.appVersion = body.appVersion ?? null;
  licence.activatedAt = licence.activatedAt ?? new Date().toISOString();
  licence.lastSeen = new Date().toISOString();

  const lease = await mintLease(env, licence, licenceId);
  await env.LICENSES.put(licRecord(licenceId), JSON.stringify(licence));

  if (firstActivation) {
    await alert(env,
      `✅ Activated: "${licence.shopName ?? licenceId}" on machine "${licence.machineName ?? '?'}" `
      + `(fingerprint ${body.fingerprint.slice(0, 16)}...)`);
  }
  return json({ licenseId: licenceId, lease: lease.token, expiresAt: lease.expiresAt });
}

/** Silent background renewal. Refusing here is what actually kills a revoked install. */
async function lease(request, env) {
  const body = await readJson(request);
  if (!body?.licenseId || !body?.fingerprint) {
    return json({ error: 'licenseId and fingerprint are required' }, 400);
  }

  const licence = await env.LICENSES.get(licRecord(body.licenseId), 'json');
  if (!licence) return json({ error: 'Unknown licence.' }, 404);

  if (licence.status === 'REVOKED') {
    return json({ error: 'This licence has been revoked.' }, 403);
  }
  if (licence.fingerprint !== body.fingerprint) {
    licence.rejectedAttempts = (licence.rejectedAttempts ?? []).slice(-49);
    licence.rejectedAttempts.push({
      at: new Date().toISOString(), fingerprint: body.fingerprint, machineName: body.machineName ?? null,
      note: 'renewal from an unbound machine',
    });
    await env.LICENSES.put(licRecord(body.licenseId), JSON.stringify(licence));
    await alert(env, `⚠️ Renewal refused for "${licence.shopName ?? body.licenseId}" - fingerprint mismatch.`);
    return json({ error: 'This licence is bound to a different PC.' }, 403);
  }

  licence.lastSeen = new Date().toISOString();
  if (body.appVersion) licence.appVersion = body.appVersion;
  const minted = await mintLease(env, licence, body.licenseId);
  await env.LICENSES.put(licRecord(body.licenseId), JSON.stringify(licence));
  return json({ lease: minted.token, expiresAt: minted.expiresAt });
}

async function adminIssue(request, env) {
  const body = await readJson(request);
  const licenceId = newId();
  const activationKey = newActivationKey();
  const licence = {
    shopName: body?.shopName ?? null,
    status: 'ACTIVE',
    activationKey,
    fingerprint: null,
    createdAt: new Date().toISOString(),
    rejectedAttempts: [],
  };
  await env.LICENSES.put(licRecord(licenceId), JSON.stringify(licence));
  await env.LICENSES.put(keyIndex(activationKey), licenceId);
  return json({ licenseId: licenceId, activationKey });
}

async function adminList(env) {
  const listing = await env.LICENSES.list({ prefix: 'lic:' });
  const licences = await Promise.all(listing.keys.map(async ({ name }) => ({
    licenseId: name.slice('lic:'.length),
    ...(await env.LICENSES.get(name, 'json')),
  })));
  return json({
    count: licences.length,
    licences,
    // Surfaced separately because this is the list the owner actually cares about.
    blockedAttempts: licences.flatMap((l) =>
      (l.rejectedAttempts ?? []).map((a) => ({ shopName: l.shopName, licenseId: l.licenseId, ...a }))),
  });
}

async function adminMutate(request, env, mutate, verb) {
  const body = await readJson(request);
  if (!body?.licenseId) return json({ error: 'licenseId is required' }, 400);
  const licence = await env.LICENSES.get(licRecord(body.licenseId), 'json');
  if (!licence) return json({ error: 'Unknown licence.' }, 404);
  mutate(licence);
  await env.LICENSES.put(licRecord(body.licenseId), JSON.stringify(licence));
  return json({ ok: true, action: verb, licenseId: body.licenseId, licence });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    try {
      if (path.startsWith('/admin')) {
        if (!authorised(request, env)) return json({ error: 'Unauthorised' }, 401);
        if (path === '/admin/activations' && request.method === 'GET') return adminList(env);
        if (path === '/admin/issue' && request.method === 'POST') return adminIssue(request, env);
        if (path === '/admin/revoke' && request.method === 'POST') {
          return adminMutate(request, env, (l) => { l.status = 'REVOKED'; }, 'revoke');
        }
        if (path === '/admin/reinstate' && request.method === 'POST') {
          return adminMutate(request, env, (l) => { l.status = 'ACTIVE'; }, 'reinstate');
        }
        // A shop legitimately replacing its PC needs this, or the only route open to them
        // is piracy. Clearing the binding lets the next /activate claim the licence.
        if (path === '/admin/unbind' && request.method === 'POST') {
          return adminMutate(request, env, (l) => { l.fingerprint = null; l.volumeSerial = null; }, 'unbind');
        }
        return json({ error: 'Not found' }, 404);
      }

      if (path === '/activate' && request.method === 'POST') return activate(request, env);
      if (path === '/lease' && request.method === 'POST') return lease(request, env);
      if (path === '/health') return json({ ok: true, leaseDays: LEASE_DAYS });
      return json({ error: 'Not found' }, 404);
    } catch (e) {
      console.error(e);
      return json({ error: 'Internal error' }, 500);
    }
  },
};
