// Local storage: wardrobe state (items + wear history) and one JPEG blob per item, all in IndexedDB.
// Nothing leaves the browser except the screenshot sent for recognition.

const DB_NAME = 'closet';
let dbp = null;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    const r = indexedDB.open(DB_NAME, 1);
    r.onupgradeneeded = () => {
      const db = r.result;
      if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
      if (!db.objectStoreNames.contains('images')) db.createObjectStore('images');
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  return dbp;
}

async function tx(store, mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const s = t.objectStore(store);
    let out;
    const req = fn(s);
    if (req) req.onsuccess = () => { out = req.result; };
    t.oncomplete = () => resolve(out);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error ?? new Error('aborted'));
  });
}

export const getKV = key => tx('kv', 'readonly', s => s.get(key));
export const setKV = (key, value) => tx('kv', 'readwrite', s => s.put(value, key));
export const getImage = id => tx('images', 'readonly', s => s.get(id));
export const putImage = (id, blob) => tx('images', 'readwrite', s => s.put(blob, id));
export const deleteImage = id => tx('images', 'readwrite', s => s.delete(id));
const allImageKeys = () => tx('images', 'readonly', s => s.getAllKeys());

/** In-memory wardrobe mirrored to IndexedDB on every change. */
export const store = {
  items: [],
  wears: {}, // 'YYYY-MM-DD' -> [ids]

  async load() {
    const st = (await getKV('state')) ?? {};
    this.items = Array.isArray(st.items) ? st.items : [];
    this.wears = st.wears && typeof st.wears === 'object' ? st.wears : {};
  },
  save() { return setKV('state', { version: 1, items: this.items, wears: this.wears }); },

  item(id) { return this.items.find(i => i.id === id); },
  async add(list) { this.items.push(...list); await this.save(); },
  async update(it) {
    const i = this.items.findIndex(x => x.id === it.id);
    if (i >= 0) { this.items[i] = it; await this.save(); }
  },
  async remove(id) {
    this.items = this.items.filter(i => i.id !== id);
    await this.save();
    await deleteImage(id).catch(() => {});
    revoke(id);
  },

  wornOn(day) { return this.wears[day] ?? null; },
  async setWorn(day, ids) {
    if (!ids?.length) delete this.wears[day]; else this.wears[day] = ids;
    await this.save();
  },
  lastWorn() {
    const out = {};
    for (const [d, ids] of Object.entries(this.wears)) for (const id of ids) if (!out[id] || d > out[id]) out[id] = d;
    return out;
  },
  wearCounts() {
    const out = {};
    for (const ids of Object.values(this.wears)) for (const id of ids) out[id] = (out[id] ?? 0) + 1;
    return out;
  },
};

// Object URLs for item photos, created once per item and revoked when the photo changes.
const urls = new Map();
export async function imageUrl(id) {
  if (urls.has(id)) return urls.get(id);
  const b = await getImage(id).catch(() => null);
  const u = b ? URL.createObjectURL(b) : null;
  urls.set(id, u);
  return u;
}
export function revoke(id) {
  const u = urls.get(id);
  if (u) URL.revokeObjectURL(u);
  urls.delete(id);
}

// ---------------- backup ----------------

const blobToDataUrl = b => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = () => rej(r.error); r.readAsDataURL(b); });

export async function exportBackup() {
  const images = {};
  for (const it of store.items) {
    const b = await getImage(it.id).catch(() => null);
    if (b) images[it.id] = await blobToDataUrl(b);
  }
  const data = { app: 'closet', version: 1, exported: new Date().toISOString(), items: store.items, wears: store.wears, images };
  return new Blob([JSON.stringify(data)], { type: 'application/json' });
}

/** Merges a backup into the current wardrobe: items with the same id are replaced, wear days are unioned. */
export async function importBackup(file) {
  let data;
  try { data = JSON.parse(await file.text()); } catch { throw new Error('这个文件不是衣橱备份。'); }
  if (data?.app !== 'closet' || !Array.isArray(data.items)) throw new Error('这个文件不是衣橱备份。');
  const valid = data.items.filter(i => i && typeof i.id === 'string' && typeof i.name === 'string');
  for (const it of valid) {
    const url = data.images?.[it.id];
    if (typeof url === 'string' && url.startsWith('data:image/')) {
      const blob = await (await fetch(url)).blob();
      await putImage(it.id, blob);
      revoke(it.id);
    }
    const i = store.items.findIndex(x => x.id === it.id);
    if (i >= 0) store.items[i] = it; else store.items.push(it);
  }
  for (const [d, ids] of Object.entries(data.wears ?? {})) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(d) || !Array.isArray(ids)) continue;
    store.wears[d] = [...new Set([...(store.wears[d] ?? []), ...ids.filter(x => typeof x === 'string')])];
  }
  await store.save();
  return valid.length;
}

/** Removes image blobs whose item no longer exists (e.g. after an interrupted delete). */
export async function sweepImages() {
  const keep = new Set(store.items.map(i => i.id));
  for (const k of await allImageKeys().catch(() => [])) if (!keep.has(k)) await deleteImage(k).catch(() => {});
}
