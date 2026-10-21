// 资讯：转发 aihot.news 的公开接口（ADR：第一版直接读 aihot），带内存缓存，并整理成 App 用的精简格式。
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync as readF, writeFileSync as writeF, existsSync as exists, readdirSync, statSync as statS, unlinkSync } from "node:fs";
const BASE = "https://aihot.news/api/v1";
const UA = "me-app/0.1 (+https://me.ken-chy129.cn; personal reader)";
const TTL = 10 * 60_000;
const cache = new Map();

async function get(path) {
  const hit = cache.get(path);
  if (hit && Date.now() - hit.at < TTL) return hit.data;
  try {
    const res = await fetch(BASE + path, { headers: { "User-Agent": UA, Accept: "application/json" }, signal: AbortSignal.timeout(12_000) });
    if (!res.ok) throw new Error(`aihot ${res.status}`);
    const data = await res.json();
    cache.set(path, { at: Date.now(), data });
    return data;
  } catch (err) {
    if (hit) return hit.data; // 拉取失败时用旧缓存
    throw err;
  }
}

const item = (x) => ({
  id: (x.links?.aihot || "").split("/").pop() || x.id || x.title,
  title: x.title,
  summary: x.summary || x.leadParagraph || "",
  source: x.source?.name || "",
  url: x.links?.original || x.links?.aihot || "",
  aihotUrl: x.links?.aihot || "",
  publishedAt: x.publishedAt || null,
});

export async function daily(date) {
  const raw = await get(date ? `/dailies/${encodeURIComponent(date)}` : "/dailies/latest");
  const r = raw.report || raw;
  const sections = (r.sections || []).map((s) => ({ label: s.label, items: (s.items || []).map(item) }));
  const lead = r.lead ? { id: sections[0]?.items[0]?.id || null, title: r.lead.title, summary: r.lead.leadParagraph || "", url: sections[0]?.items[0]?.url || r.links?.aihot } : null;
  return {
    date: r.date,
    generatedAt: r.generatedAt,
    lead,
    sections,
    flashes: (r.flashes || []).map(item),
    url: r.links?.aihot,
    attribution: "内容来自 AIHOT（aihot.news）",
  };
}

export async function hot() {
  const raw = await get("/hot-topics");
  return {
    items: (raw.items || []).map((x) => ({
      ...item(x),
      rank: x.rank,
      sourceCount: x.sourceCount || 0,
      sources: (x.sourceNames || []).slice(0, 5),
      latestAt: x.latestAt || null,
    })),
    attribution: "内容来自 AIHOT（aihot.news）",
  };
}

/* ---------- 正文 ---------- */

// AIHOT 网站自己渲染详情页用的接口，返回整理过的中文正文（HTML）
const SITE = "https://aihot.news";
const ITEM_TTL = 60 * 60_000;
const itemCache = new Map();

/** 正文 HTML 收敛到一小组安全标签，图片地址补成绝对路径 */
export function cleanBody(html) {
  if (!html) return "";
  let s = String(html)
    .replace(/<(script|style|iframe|object|embed|form|noscript)[\s\S]*?<\/\1>/gi, "")
    .replace(/<(script|style|iframe|object|embed|form|input|button|link|meta)\b[^>]*>/gi, "")
    .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "")
    .replace(/\s(srcset|sizes|style|class|id)\s*=\s*("[^"]*"|'[^']*')/gi, "")
    .replace(/(href|src)\s*=\s*"\s*javascript:[^"]*"/gi, '$1="#"');
  s = s.replace(/\s(src|href)="\/(?!\/)/g, ` $1="${SITE}/`);
  return s;
}

export async function article(id) {
  return articleImpl(id);
}

/* ---------- 正文图片中转 ---------- */
// 手机直连 aihot 的图片代理不一定通（国内网络 / 运营商），失败时由这里代取，并在磁盘上缓存一份
const IMG_DIR = process.env.ME_NEWS_IMG_DIR || "/var/lib/me/news-img";
const IMG_MAX_FILES = 400;
const ALLOWED_IMG_HOSTS = new Set(["aihot.news"]);

export async function image(rawUrl) {
  let u;
  try { u = new URL(String(rawUrl || "")); } catch { throw Object.assign(new Error("图片地址无效"), { status: 400 }); }
  if (u.protocol !== "https:" || !ALLOWED_IMG_HOSTS.has(u.hostname)) throw Object.assign(new Error("不支持的图片地址"), { status: 400 });
  const key = createHash("sha1").update(u.href).digest("hex");
  const file = `${IMG_DIR}/${key}`;
  if (exists(file) && exists(file + ".type")) return { type: readF(file + ".type", "utf8"), body: readF(file) };
  const res = await fetch(u.href, { headers: { "User-Agent": UA, Accept: "image/*" }, signal: AbortSignal.timeout(20_000) });
  const type = res.headers.get("content-type") || "";
  if (!res.ok || !type.startsWith("image/")) throw Object.assign(new Error(`图片获取失败（${res.status}）`), { status: 502 });
  const body = Buffer.from(await res.arrayBuffer());
  if (body.length > 15_000_000) throw Object.assign(new Error("图片过大"), { status: 502 });
  try {
    mkdirSync(IMG_DIR, { recursive: true });
    writeF(file, body);
    writeF(file + ".type", type);
    pruneImages();
  } catch (err) {
    console.warn("[news] 图片缓存写入失败", err.message);
  }
  return { type, body };
}

function pruneImages() {
  const files = readdirSync(IMG_DIR).filter((f) => !f.endsWith(".type"));
  if (files.length <= IMG_MAX_FILES) return;
  files
    .map((f) => ({ f, t: statS(`${IMG_DIR}/${f}`).mtimeMs }))
    .sort((a, b) => a.t - b.t)
    .slice(0, files.length - IMG_MAX_FILES)
    .forEach(({ f }) => { for (const p of [f, f + ".type"]) try { unlinkSync(`${IMG_DIR}/${p}`); } catch {} });
}

async function articleImpl(id) {
  if (!/^[a-zA-Z0-9_-]{1,80}$/.test(id)) throw Object.assign(new Error("条目不存在"), { status: 404 });
  const hit = itemCache.get(id);
  if (hit && Date.now() - hit.at < ITEM_TTL) return hit.data;
  const res = await fetch(`${SITE}/api/site/items/${id}`, {
    headers: { "User-Agent": UA, Accept: "application/json" },
    signal: AbortSignal.timeout(12_000),
  });
  if (res.status === 404) throw Object.assign(new Error("条目不存在"), { status: 404 });
  if (!res.ok) {
    if (hit) return hit.data;
    throw new Error(`aihot ${res.status}`);
  }
  const x = await res.json();
  const body = x.body?.zh || x.body?.original || "";
  const data = {
    id,
    title: x.title || "",
    originalTitle: x.originalTitle && x.originalTitle !== x.title ? x.originalTitle : null,
    summary: x.summary || "",
    source: x.source?.name || "",
    author: x.author || null,
    publishedAt: x.publishedAt || x.discoveredAt || null,
    tags: (x.tags || []).slice(0, 5),
    url: x.links?.original || `${SITE}/items/${id}`,
    translated: Boolean(x.body?.zh && x.hasTranslation),
    complete: x.readingMode === "full" || Boolean(x.body?.complete),
    sourceCount: (x.group?.additionalSourceCount || 0) + 1,
    html: cleanBody(body),
    attribution: "内容来自 AIHOT（aihot.news），版权归原作者",
  };
  itemCache.set(id, { at: Date.now(), data });
  if (itemCache.size > 300) itemCache.delete(itemCache.keys().next().value);
  return data;
}
