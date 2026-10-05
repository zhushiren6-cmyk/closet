// Image decoding, scaling and cropping on a canvas.

/** Decodes a picked file (EXIF rotation applied by the browser), short side ≤1080, long side ≤4096. */
export async function decode(file) {
  let src;
  try {
    src = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    src = await new Promise((resolve, reject) => {
      const img = new Image();
      const u = URL.createObjectURL(file);
      img.onload = () => { URL.revokeObjectURL(u); resolve(img); };
      img.onerror = () => { URL.revokeObjectURL(u); reject(new Error('读不出这张图')); };
      img.src = u;
    });
  }
  const w = src.width, h = src.height;
  if (!w || !h) throw new Error('读不出这张图');
  const s = Math.min(1, 1080 / Math.min(w, h), 4096 / Math.max(w, h));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * s));
  c.height = Math.max(1, Math.round(h * s));
  const g = c.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.drawImage(src, 0, 0, c.width, c.height);
  src.close?.();
  return c;
}

export const toDataUrl = (canvas, q = 0.85) => canvas.toDataURL('image/jpeg', q);

export const toBlob = (canvas, q = 0.88) =>
  new Promise((res, rej) => canvas.toBlob(b => (b ? res(b) : rej(new Error('图片保存失败'))), 'image/jpeg', q));

/** Crops [l,t,r,b] (pixels of [src]) and scales to ≤640 px on the long side. */
export function crop(src, box) {
  const l = Math.max(0, Math.min(src.width - 1, Math.round(box[0])));
  const t = Math.max(0, Math.min(src.height - 1, Math.round(box[1])));
  const r = Math.max(l + 1, Math.min(src.width, Math.round(box[2])));
  const b = Math.max(t + 1, Math.min(src.height, Math.round(box[3])));
  const w = r - l, h = b - t;
  const s = Math.min(1, 640 / Math.max(w, h));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * s));
  c.height = Math.max(1, Math.round(h * s));
  const g = c.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.drawImage(src, l, t, w, h, 0, 0, c.width, c.height);
  return c;
}
