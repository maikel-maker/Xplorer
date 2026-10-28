// Pont vers Android (notifications). Sans effet dans un navigateur.
const LN = () => globalThis.Capacitor?.Plugins?.LocalNotifications;

export const isNative = () => !!globalThis.Capacitor?.isNativePlatform?.();

export async function askNotifications() {
  const ln = LN(); if (!ln) return false;
  try {
    let p = await ln.checkPermissions();
    if (p.display !== 'granted') p = await ln.requestPermissions();
    return p.display === 'granted';
  } catch { return false; }
}

let nextId = Math.floor(Date.now() / 1000) % 1e9;
export async function notify(title, body) {
  const ln = LN(); if (!ln) return;
  try {
    if (!(await askNotifications())) return;
    await ln.schedule({ notifications: [{ id: nextId++, title, body }] });
  } catch { /* notification impossible : pas bloquant */ }
}
