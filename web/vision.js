// Model providers, the recognition prompt, response parsing and the browser client.
import { normalizeCat, normalizeColor, OCCASIONS } from './engine.js';

/**
 * Vision-capable, OpenAI-compatible providers. Same list and defaults as the Android app.
 * Whether each one allows direct calls from a browser (CORS) is checked with 「测试连接」.
 */
export const PROVIDERS = [
  { id: 'DOUBAO', label: '豆包（火山方舟）', endpoint: 'https://ark.cn-beijing.volces.com/api/v3/chat/completions', model: 'doubao-seed-2-0-mini-260428', thinking: true },
  { id: 'QWEN', label: '通义千问（阿里百炼）', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', model: 'qwen3-vl-plus' },
  { id: 'ZHIPU', label: '智谱 GLM', endpoint: 'https://open.bigmodel.cn/api/paas/v4/chat/completions', model: 'glm-4.6v', thinking: true },
  { id: 'SILICONFLOW', label: '硅基流动', endpoint: 'https://api.siliconflow.cn/v1/chat/completions', model: 'Qwen/Qwen3-VL-32B-Instruct' },
  { id: 'OPENAI', label: 'OpenAI', endpoint: 'https://api.openai.com/v1/chat/completions', model: 'gpt-5-mini', noTemp: true, newTokens: true, overseas: true },
  { id: 'GEMINI', label: 'Google Gemini', endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions', model: 'gemini-3.8-flash', overseas: true },
  { id: 'OPENROUTER', label: 'OpenRouter', endpoint: 'https://openrouter.ai/api/v1/chat/completions', model: 'openai/gpt-5-mini', noTemp: true, overseas: true },
  { id: 'CUSTOM', label: '自定义（OpenAI 兼容）', endpoint: '', model: '' },
];
export const providerOf = id => PROVIDERS.find(p => p.id === id) ?? PROVIDERS[0];

export const SYSTEM_PROMPT = `你是衣橱录入助手。用户发来一张图片，可能是购物订单截图（淘宝、京东、拼多多、抖音、得物等，可能有一件或多件商品），也可能是衣物的实拍照片。
请找出图里用户买的或拍的所有衣服、鞋子、包和配饰。忽略非服饰商品，忽略“为你推荐”“猜你喜欢”等推荐区域里的商品，同一件商品出现多次只输出一次。

每件输出：
- name：12 个字以内，颜色+款式，例如“黑色宽松连帽卫衣”，去掉品牌名和营销词
- cat：只能是 上装、下装、连衣裙、外套、鞋子、包、配饰 之一（半身裙算下装）
- color：主色，只能是 黑、白、灰、米、卡其、驼、棕、藏青、牛仔蓝、蓝、绿、红、粉、黄、橙、紫、花色 之一
- warmth：厚度，1=薄（夏季）、2=适中（春秋）、3=厚（冬季）
- occasions：适合的场合，从 日常、通勤、约会、运动、正式 中选 1-3 个
- box：[x1, y1, x2, y2]，商品图片在整张图中的位置，用 0-1000 的相对坐标（左上角为 0,0，右下角为 1000,1000）。订单截图框商品的缩略图，不要框文字；实拍照片框衣物本身。

只输出一个 JSON 对象，不要代码围栏，不要任何解释：
{"items":[{"name":"...","cat":"...","color":"...","warmth":2,"occasions":["日常"],"box":[x1,y1,x2,y2]}]}
如果图里没有服饰，输出 {"items":[],"note":"原因"}`;

export class VisionError extends Error {
  constructor(message, raw = '') { super(message); this.raw = raw; }
}

/**
 * Accepts [x1,y1,x2,y2] as 0–1000 relative (asked for), 0–1 fractions, or absolute pixels.
 * Returns [l,t,r,b] in pixels of a w×h image with a small margin, or null when unusable.
 */
export function parseBox(v, w, h) {
  if (!Array.isArray(v) || v.length < 4 || w <= 0 || h <= 0) return null;
  const n = v.slice(0, 4).map(Number);
  if (n.some(x => !Number.isFinite(x) || x < 0)) return null;
  const max = Math.max(...n);
  const [sx, sy] = max <= 1 ? [w, h] : max <= 1000 ? [w / 1000, h / 1000] : [1, 1];
  const clamp = (x, hi) => Math.min(hi, Math.max(0, x));
  let x1 = clamp(Math.trunc(Math.min(n[0], n[2]) * sx), w), x2 = clamp(Math.trunc(Math.max(n[0], n[2]) * sx), w);
  let y1 = clamp(Math.trunc(Math.min(n[1], n[3]) * sy), h), y2 = clamp(Math.trunc(Math.max(n[1], n[3]) * sy), h);
  if (x2 - x1 < w * 0.03 || y2 - y1 < h * 0.01 || x2 - x1 < 16 || y2 - y1 < 16) return null;
  const px = Math.trunc((x2 - x1) * 0.03), py = Math.trunc((y2 - y1) * 0.03);
  x1 = Math.max(0, x1 - px); x2 = Math.min(w, x2 + px);
  y1 = Math.max(0, y1 - py); y2 = Math.min(h, y2 + py);
  return [x1, y1, x2, y2];
}

/** Tolerates code fences, prose around the JSON, and a bare top-level array. Returns { items, note }. */
export function parseResult(content, w, h) {
  const text = String(content).trim();
  const o = text.indexOf('{'), a = text.indexOf('[');
  let arr, note = '';
  if (a >= 0 && (o < 0 || a < o)) {
    try { arr = JSON.parse(text.slice(a, text.lastIndexOf(']') + 1)); }
    catch { throw new VisionError('模型返回的内容无法解析。', text.slice(0, 600)); }
  } else {
    const end = text.lastIndexOf('}');
    if (o < 0 || end <= o) throw new VisionError('模型没有返回 JSON。', text.slice(0, 600));
    let obj;
    try { obj = JSON.parse(text.slice(o, end + 1)); }
    catch { throw new VisionError('模型返回的 JSON 无法解析。', text.slice(0, 600)); }
    note = String(obj.note ?? '').trim();
    arr = Array.isArray(obj.items) ? obj.items : [];
  }
  if (!Array.isArray(arr)) arr = [];
  const items = [];
  for (const it of arr) {
    if (!it || typeof it !== 'object') continue;
    const name = String(it.name ?? '').trim().slice(0, 20);
    if (!name) continue;
    const occ = Array.isArray(it.occasions)
      ? [...new Set(it.occasions.map(x => String(x).trim()).filter(x => OCCASIONS.includes(x)))] : [];
    items.push({
      name,
      cat: normalizeCat(it.cat || name),
      color: normalizeColor(it.color || name),
      warmth: Math.min(3, Math.max(1, parseInt(it.warmth, 10) || 2)),
      occasions: occ,
      box: parseBox(it.box, w, h),
    });
  }
  return { items, note };
}

export function endpointOf(s, p) {
  if (p.id !== 'CUSTOM') return p.endpoint;
  const raw = (s.endpoint?.CUSTOM ?? '').trim().replace(/\/+$/, '');
  if (!raw) return '';
  return raw.endsWith('/chat/completions') ? raw : raw + '/chat/completions';
}
export const modelOf = (s, p) => (s.model?.[p.id] ?? '').trim() || p.model;

/** Empty string when usable, otherwise what is missing. [s] is the settings object. */
export function missingOf(s, p) {
  if (!(s.key?.[p.id] ?? '').trim()) return `还没填写 ${p.label} 的 API Key`;
  if (p.id === 'CUSTOM') {
    const e = endpointOf(s, p);
    if (!e) return '还没填写自定义接口地址';
    if (!(s.model?.CUSTOM ?? '').trim()) return '还没填写自定义模型名';
    if (!e.startsWith('https://')) return '接口地址要以 https:// 开头';
  }
  return '';
}

function hint(code) {
  return ({ 400: '请求被拒绝，多半是这个模型不支持看图，请换一个能看图的模型', 401: 'API Key 无效或没有权限', 403: 'API Key 无效或没有权限',
    402: '账户余额不足', 404: '模型名或接口地址不存在，请检查模型名', 413: '图片太大', 429: '请求太频繁或额度用尽，稍等再试' })[code]
    ?? (code >= 500 ? '模型服务暂时出错，稍后重试' : '请求失败');
}

/** One chat completion. [userContent] is a string or a content array. Returns the reply text. */
export async function chat(s, userContent, { system = SYSTEM_PROMPT, maxTokens = 4096, thinking, timeoutMs = 120000 } = {}) {
  const p = providerOf(s.provider);
  const missing = missingOf(s, p);
  if (missing) throw new VisionError(`${missing}，请到「设置」里填写。`);
  const body = {
    model: modelOf(s, p),
    [p.newTokens ? 'max_completion_tokens' : 'max_tokens']: maxTokens,
    messages: [{ role: 'system', content: system }, { role: 'user', content: userContent }],
  };
  if (!p.noTemp) body.temperature = 0.2;
  const useThinking = thinking ?? !!p.thinking;
  if (useThinking) body.thinking = { type: 'disabled' };

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  let res;
  try {
    res = await fetch(endpointOf(s, p), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.key[p.id].trim()}` },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') throw new VisionError('请求超时，网络慢或图片太大，请重试。');
    // fetch only rejects like this on network failure or when the server does not allow browser (CORS) calls.
    throw new VisionError(`连不上 ${p.label}。可能是网络问题，也可能是这家服务不允许网页直接调用，可以换一家试试。`, String(e.message ?? e));
  } finally { clearTimeout(timer); }

  const text = await res.text();
  if (!res.ok) {
    if (useThinking && res.status === 400) return chat(s, userContent, { system, maxTokens, thinking: false, timeoutMs });
    throw new VisionError(`${hint(res.status)}（HTTP ${res.status}）`, text.slice(0, 600));
  }
  let root;
  try { root = JSON.parse(text); } catch { throw new VisionError('模型返回格式无法解析。', text.slice(0, 600)); }
  if (root.error) throw new VisionError(`模型服务返回错误：${root.error.message ?? ''}`, text.slice(0, 600));
  const content = root.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || !content.trim()) throw new VisionError('模型返回了空内容，可能是模型不支持看图，换个模型试试。', text.slice(0, 600));
  return content;
}

export async function recognize(s, jpegDataUrl, w, h) {
  const content = await chat(s, [
    { type: 'image_url', image_url: { url: jpegDataUrl } },
    { type: 'text', text: '识别这张图里的服饰。' },
  ]);
  return parseResult(content, w, h);
}

/** Tiny text-only request: checks key, model name, and whether the browser may call this provider at all. */
export async function testConnection(s) {
  const reply = await chat(s, '回复“好”一个字。', { system: '你是测试助手。', maxTokens: 300, timeoutMs: 30000 });
  return reply.trim().slice(0, 20);
}
