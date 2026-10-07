// Bridge to the Android shell (window.ClosetAndroid). In a normal browser none of this is used.
const A = globalThis.ClosetAndroid;
export const isAndroid = !!A;

let seq = 0;
const pending = new Map();
globalThis.__closetNative = (id, status, text) => {
  const done = pending.get(id);
  if (done) { pending.delete(id); done(status, text); }
};

/**
 * POST through native code, so model APIs that block browser (CORS) calls still work.
 * Resolves { status, text }; rejects with name 'TimeoutError' or a network error.
 */
export function nativePost(url, headers, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, (status, text) => {
      if (status === -1) reject(Object.assign(new Error('timeout'), { name: 'TimeoutError' }));
      else if (status < 0) reject(new Error(text));
      else resolve({ status, text });
    });
    try { A.http(id, url, JSON.stringify(headers), body, timeoutMs); }
    catch (e) { pending.delete(id); reject(e); }
  });
}

/** Asks the user where to save [text]. */
export const saveFile = (name, text) => A.saveFile(name, text);

/** The old native wardrobe as a backup JSON string, once; null if none. */
export function legacyBackup() {
  try { return A?.legacyBackup?.() ?? null; } catch { return null; }
}
export const legacyDone = () => { try { A?.legacyDone?.(); } catch { /* ignore */ } };
