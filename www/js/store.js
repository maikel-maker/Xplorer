// Stockage local sur le téléphone (rien n'est envoyé ailleurs).
const P = 'xp.';

export function load(key, fallback = null) {
  try { const v = localStorage.getItem(P + key); return v == null ? fallback : JSON.parse(v); }
  catch { return fallback; }
}
export function save(key, value) {
  try { localStorage.setItem(P + key, JSON.stringify(value)); } catch { /* stockage plein ou bloqué */ }
}
export function remove(key) { try { localStorage.removeItem(P + key); } catch { /* rien */ } }

export const DEFAULT_ASSETS = [
  { symbol: 'BTC',  name: 'Bitcoin',   cg: 'bitcoin',   target: 0.45 },
  { symbol: 'ETH',  name: 'Ethereum',  cg: 'ethereum',  target: 0.20 },
  { symbol: 'GEOD', name: 'Geodnet',   cg: 'geodnet',   target: 0.20 },
  { symbol: 'LINK', name: 'Chainlink', cg: 'chainlink', target: 0.05 },
  { symbol: 'TAO',  name: 'Bittensor', cg: 'bittensor', target: 0.05 },
  { symbol: 'KRL',  name: 'Kryll',     cg: 'kryll',     target: 0.05 },
];

// Monnaies qui ne sont pas des cryptos à suivre
export const CASH = ['EUR', 'USD', 'USDC', 'USDT', 'EURC', 'GBP'];

export function settings() {
  const s = load('settings', {});
  return {
    monthly: 500,
    geodBase: 0.21,
    geodMult: 20,
    notifications: true,
    alerts: [],
    ...s,
    assets: s.assets?.length ? s.assets : DEFAULT_ASSETS,
  };
}
export function saveSettings(s) { save('settings', s); }

export function creds() { return load('cb', null); }
export function saveCreds(c) { save('cb', c); }
export function clearCreds() { remove('cb'); }
