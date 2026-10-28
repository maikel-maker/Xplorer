// Coinbase Advanced — lecture seule (clé CDP, algorithme ECDSA).
// La clé reste sur le téléphone. Aucun ordre n'est jamais envoyé : l'app ne fait que des GET.

const HOST = 'api.coinbase.com';
const subtle = () => globalThis.crypto.subtle;

// ----- outils base64 / DER -----
const b64ToBytes = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
const bytesToB64url = bytes => {
  let s = ''; bytes.forEach(b => { s += String.fromCharCode(b); });
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const strToB64url = str => bytesToB64url(new TextEncoder().encode(str));

function readDer(bytes, pos) {
  const tag = bytes[pos];
  let len = bytes[pos + 1], off = pos + 2;
  if (len & 0x80) {
    const n = len & 0x7f; len = 0;
    for (let i = 0; i < n; i++) len = (len << 8) | bytes[off + i];
    off += n;
  }
  return { tag, start: off, end: off + len };
}

// Accepte : JSON téléchargé chez Coinbase, ou nom + clé PEM séparés.
export function parseKeyInput(nameInput, keyInput) {
  let name = (nameInput || '').trim();
  let pem = (keyInput || '').trim();
  const tryJson = txt => { try { return JSON.parse(txt); } catch { return null; } };
  const j = tryJson(pem) || tryJson(name);
  if (j) { name = j.name || j.id || name; pem = j.privateKey || pem; }
  pem = pem.replace(/\\n/g, '\n');
  if (!name || !pem.includes('PRIVATE KEY')) throw new Error('Clé incomplète : il faut le nom (organizations/…/apiKeys/…) et la clé privée.');
  if (!name.includes('apiKeys/')) throw new Error('Le nom de la clé doit ressembler à organizations/…/apiKeys/…');
  return { name, pem };
}

async function importKey(pem) {
  const body = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const der = b64ToBytes(body);
  if (pem.includes('BEGIN PRIVATE KEY')) {
    return subtle().importKey('pkcs8', der, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  }
  if (!pem.includes('BEGIN EC PRIVATE KEY')) {
    throw new Error('Cette clé n\'est pas une clé ECDSA. Crée la clé Coinbase avec l\'algorithme ECDSA.');
  }
  // SEC1 : SEQUENCE { version, OCTET STRING d, [0] params, [1] BIT STRING pubkey }
  const seq = readDer(der, 0);
  let p = seq.start;
  const ver = readDer(der, p); p = ver.end;
  const dEl = readDer(der, p); p = dEl.end;
  let d = der.slice(dEl.start, dEl.end);
  let pub = null;
  while (p < seq.end) {
    const el = readDer(der, p);
    if (el.tag === 0xa1) {
      const bit = readDer(der, el.start);
      pub = der.slice(bit.start + 1, bit.end); // saute l'octet "bits inutilisés"
    }
    p = el.end;
  }
  if (!pub || pub[0] !== 4) throw new Error('Clé privée illisible (clé publique absente).');
  if (d.length > 32) d = d.slice(d.length - 32);
  const jwk = {
    kty: 'EC', crv: 'P-256', ext: true,
    d: bytesToB64url(d), x: bytesToB64url(pub.slice(1, 33)), y: bytesToB64url(pub.slice(33, 65)),
  };
  return subtle().importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

export async function buildJwt(name, pem, method, path, now = Math.floor(Date.now() / 1000)) {
  const key = await importKey(pem);
  const nonce = [...globalThis.crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
  const header = { alg: 'ES256', kid: name, nonce, typ: 'JWT' };
  const payload = { sub: name, iss: 'cdp', nbf: now, exp: now + 120, uri: `${method} ${HOST}${path}` };
  const input = `${strToB64url(JSON.stringify(header))}.${strToB64url(JSON.stringify(payload))}`;
  const sig = new Uint8Array(await subtle().sign({ name: 'ECDSA', hash: 'SHA-256' }, key, new TextEncoder().encode(input)));
  return `${input}.${bytesToB64url(sig)}`;
}

async function get(creds, path, query = '') {
  const jwt = await buildJwt(creds.name, creds.pem, 'GET', path);
  const res = await fetch(`https://${HOST}${path}${query}`, { headers: { Authorization: `Bearer ${jwt}` } });
  if (res.status === 401) throw new Error('Coinbase refuse la clé (401). Vérifie qu\'elle est en ECDSA avec la permission « View ».');
  if (!res.ok) throw new Error(`Coinbase a répondu ${res.status}.`);
  return res.json();
}

// Soldes : { BTC: 0.0017, EUR: 135, ... }
export async function fetchBalances(creds) {
  const out = {};
  let cursor = '';
  for (let page = 0; page < 10; page++) {
    const q = `?limit=250${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const data = await get(creds, '/api/v3/brokerage/accounts', q);
    for (const a of data.accounts || []) {
      const v = parseFloat(a.available_balance?.value || 0) + parseFloat(a.hold?.value || 0);
      if (v > 0) out[a.currency] = (out[a.currency] || 0) + v;
    }
    if (!data.has_next || !data.cursor) break;
    cursor = data.cursor;
  }
  return out;
}

// Achats/ventes exécutés : [{ t, symbol, quote, side, qty, price, fee }]
export async function fetchFills(creds) {
  const out = [];
  let cursor = '';
  for (let page = 0; page < 20; page++) {
    const q = `?limit=250${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    const data = await get(creds, '/api/v3/brokerage/orders/historical/fills', q);
    for (const f of data.fills || []) {
      const [base, quote] = (f.product_id || '').split('-');
      const price = parseFloat(f.price);
      const size = parseFloat(f.size);
      if (!base || !(price > 0) || !(size > 0)) continue;
      const qty = f.size_in_quote ? size / price : size;
      out.push({
        t: Date.parse(f.trade_time), symbol: base, quote, side: f.side,
        qty, price, fee: parseFloat(f.commission || 0), id: f.entry_id || f.trade_id,
      });
    }
    if (!data.cursor) break;
    cursor = data.cursor;
  }
  return out.sort((a, b) => a.t - b.t);
}
