// 「我」的后端（ADR 0001）：一个服务承载所有模块。
//
//   me.ken-chy129.cn  一个域名：
//     网页（储蓄、待办）：静态页面 + /api/*（Cookie 登录）
//     App / uTools：/api/*（Bearer 设备凭证），/download/*，/codex/machine（机器接入）
import { createServer } from "node:http";
import { gzipSync } from "node:zlib";
import { createReadStream, existsSync, readFileSync, statSync } from "node:fs";
import { extname, join, resolve, sep } from "node:path";
import { CodexHub } from "./codex/hub.mjs";
import { Alerts } from "./alerts.mjs";
import { WebSocketServer } from "ws";
import { deviceFor, pair } from "./devices.mjs";
import * as news from "./news.mjs";
import { savingsRoute } from "./savings/routes.mjs";
import { InputError, load } from "./savings/store.mjs";
import { TodoError, todosRoute } from "./todos.mjs";
import { parseTodo } from "./todo-parse.mjs";
import * as asr from "./asr.mjs";
import {
  clearFailures, clearSessionCookie, createSessionCookie, isAuthenticated, loginBlocked, recordFailure, verifyLogin,
} from "./web-auth.mjs";

const PORT = Number(process.env.PORT || 8795);
const HOST = process.env.HOST || "127.0.0.1";
const SECURE = process.env.NODE_ENV === "production";
const LEDGER_DIST = resolve(process.env.LEDGER_DIST || "/root/apps/me/web/dist");
const DOWNLOADS = resolve(process.env.ME_DOWNLOADS || "/var/lib/me/downloads");

const alerts = new Alerts();
const hub = new CodexHub(alerts);

// App 的常驻连接：/api/alerts/live?after=<最后收到的序号>
const alertWss = new WebSocketServer({ noServer: true });
function alertsUpgrade(req, socket, head) {
  const url = new URL(req.url, "http://x");
  if (url.pathname !== "/api/alerts/live") return false;
  if (!deviceOk(req)) {
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return true;
  }
  alertWss.handleUpgrade(req, socket, head, (ws) => {
    const send = (a) => ws.readyState === 1 && ws.send(JSON.stringify({ type: "alert", alert: a }));
    ws.send(JSON.stringify({ type: "hello", last: alerts.last }));
    for (const a of alerts.since(Number(url.searchParams.get("after")) || 0)) send(a);
    const off = alerts.subscribe(send);
    // 服务器侧也定期 ping，帮手机和中间的网络设备保持这条连接不被掐掉
    const ping = setInterval(() => (ws.readyState === 1 ? ws.ping() : clearInterval(ping)), 45_000);
    ws.on("close", () => {
      off();
      clearInterval(ping);
    });
    ws.on("error", () => {});
  });
  return true;
}

/** 待办有变化：经 App 的常驻连接告诉手机去拉最新的，好重排到点提醒 */
function todosChanged() {
  const msg = JSON.stringify({ type: "todos" });
  for (const ws of alertWss.clients) if (ws.readyState === 1) ws.send(msg);
}

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".apk": "application/vnd.android.package-archive",
  ".json": "application/json",
};

function send(res, status, body, headers = {}) {
  const raw = typeof body === "string";
  const text = raw ? body : JSON.stringify(body);
  const h = {
    "Content-Type": raw ? "text/plain; charset=utf-8" : "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    ...headers,
  };
  // 会话内容是大段文字，压缩后约为原来的 1/3
  if (text.length > 8_000 && /\bgzip\b/.test(String(res.req?.headers["accept-encoding"] || ""))) {
    const z = gzipSync(text, { level: 5 });
    res.writeHead(status, { ...h, "Content-Encoding": "gzip", "Content-Length": z.length, Vary: "Accept-Encoding" });
    return res.end(z);
  }
  res.writeHead(status, h);
  res.end(text);
}

const BIG_BODY = 20_000_000; // 带图片的 Codex 消息

async function readJson(req, limit = 256_000) {
  if (!String(req.headers["content-type"] || "").includes("application/json")) throw new InputError("需要 JSON 请求", 415);
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new InputError("请求体过大", 413);
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw new InputError("JSON 格式错误");
  }
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try {
    return new URL(origin).host === req.headers.host;
  } catch {
    return false;
  }
}

const clientIp = (req) => String(req.headers["x-forwarded-for"] || "").split(",")[0].trim() || req.socket.remoteAddress || "?";

/** 公积金余额 = 累计缴存 − 提取；设置里填了某月实际余额就从那个月校准 */
function fundBalanceOf(sorted, settings) {
  let fund = 0;
  for (const r of sorted) {
    fund += (r.fund || 0) - (r.fundWithdrawal || 0);
    if (settings.fundBalance && settings.fundBalance.asOf === r.month) fund = settings.fundBalance.amount;
  }
  return Math.max(0, fund);
}

function sendExport(res, data) {
  const stamp = new Date().toISOString().slice(0, 10);
  send(res, 200, JSON.stringify(data, null, 2), {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Disposition": `attachment; filename="ledger-${stamp}.json"`,
  });
}

/* ---------- 网站（ledger） ---------- */

async function webApi(req, res, path) {
  const method = req.method || "GET";
  if (path === "/api/session" && method === "GET") return send(res, 200, { authenticated: isAuthenticated(req.headers.cookie) });
  if (method !== "GET" && !sameOrigin(req)) return send(res, 403, { error: "跨站请求被拒绝" });
  if (path === "/api/login" && method === "POST") {
    const ip = clientIp(req);
    if (loginBlocked(ip)) return send(res, 429, { error: "尝试次数过多，请 15 分钟后再试" });
    const body = await readJson(req);
    if (!verifyLogin(body.username, body.password)) {
      recordFailure(ip);
      return send(res, 401, { error: "账号或密码不正确" });
    }
    clearFailures(ip);
    return send(res, 200, { ok: true }, { "Set-Cookie": createSessionCookie(SECURE) });
  }
  if (path === "/api/logout" && method === "POST") return send(res, 200, { ok: true }, { "Set-Cookie": clearSessionCookie(SECURE) });
  if (!isAuthenticated(req.headers.cookie)) return send(res, 401, { error: "请先登录" });

  // 电脑上写待办 / 备忘：和 App 用同一份数据，改完通知手机重排提醒
  if (path === "/api/todos" || path.startsWith("/api/todos/")) {
    const out = await todosRoute(method, path.slice("/api/todos".length), () => readJson(req));
    if (!out) return send(res, 404, { error: "接口不存在" });
    if (method !== "GET") todosChanged();
    return send(res, out[0], out[1]);
  }

  const out = await savingsRoute(method, path.slice(4), () => readJson(req));
  if (!out) return send(res, 404, { error: "接口不存在" });
  if (out[0] === "export") return sendExport(res, out[1]);
  return send(res, out[0], out[1]);
}

function serveStatic(res, root, path, fallback = "index.html") {
  let file;
  try {
    file = resolve(root, "." + decodeURIComponent(path));
  } catch {
    return send(res, 400, "Bad Request");
  }
  if (file !== root && !file.startsWith(root + sep)) return send(res, 403, "Forbidden");
  if (!existsSync(file) || statSync(file).isDirectory()) {
    // 旧版本的 js/css 已被新构建删掉：返回 404，别拿 index.html 冒充脚本
    if (!fallback || path.startsWith("/assets/")) return send(res, 404, "Not Found");
    file = join(root, fallback);
  }
  if (!existsSync(file)) return send(res, 503, "前端尚未构建");
  const body = readFileSync(file);
  res.writeHead(200, {
    "Content-Type": MIME[extname(file)] || "application/octet-stream",
    "Content-Length": body.length,
    "Cache-Control": file.includes(`${sep}assets${sep}`) ? "public, max-age=31536000, immutable" : "no-store, max-age=0",
  });
  res.end(body);
}

/* ---------- App ---------- */

/** 安装包下载：支持 Range 断点续传（网络抖一下不用从头下） */
function serveDownload(req, res, name) {
  if (!/^[\w.-]+\.(apk|zip)$/.test(name)) return send(res, 404, "Not Found");
  const file = join(DOWNLOADS, name);
  if (!existsSync(file)) return send(res, 404, "Not Found");
  const size = statSync(file).size;
  const zip = name.endsWith(".zip");
  const headers = {
    "Content-Type": zip ? "application/zip" : "application/vnd.android.package-archive",
    "Accept-Ranges": "bytes",
    // 带版本号的包内容不会变，可以长缓存；me.apk 是「最新」的软链
    "Cache-Control": name === "me.apk" || zip ? "no-store" : "public, max-age=31536000, immutable",
  };
  const m = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || "");
  if (m) {
    const start = Number(m[1]);
    const end = m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
    if (start >= size || start > end) {
      res.writeHead(416, { ...headers, "Content-Range": `bytes */${size}` });
      return res.end();
    }
    res.writeHead(206, { ...headers, "Content-Length": end - start + 1, "Content-Range": `bytes ${start}-${end}/${size}` });
    return createReadStream(file, { start, end }).pipe(res);
  }
  res.writeHead(200, { ...headers, "Content-Length": size });
  createReadStream(file).pipe(res);
}

const deviceOk = (req) => Boolean(deviceFor(req.headers.authorization));

async function appApi(req, res, path) {
  const method = req.method || "GET";

  if (path === "/api/pair" && method === "POST") {
    const body = await readJson(req);
    const result = pair(body.code, body.name);
    if (result.error) return send(res, 400, result);
    console.log(`[me] 新设备已配对：${result.device.name}`);
    return send(res, 200, result);
  }
  if (path === "/api/version" && method === "GET") {
    const file = join(DOWNLOADS, "latest.json");
    return send(res, 200, existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : { versionCode: 0 });
  }

  const device = deviceFor(req.headers.authorization);
  if (!device) return send(res, 401, { error: "设备未配对或已被吊销" });

  // 整段语音识别：body 是 16k 单声道 int16 PCM（最长 3 分钟）
  if (path === "/api/asr" && method === "POST") {
    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > 16000 * 2 * 180) return send(res, 413, { error: "太长了" });
      chunks.push(c);
    }
    return send(res, 200, { text: await asr.recognize(Buffer.concat(chunks)) });
  }

  if (path === "/api/home" && method === "GET") {
    const [codex, daily] = await Promise.allSettled([hub.sessions(), news.daily()]);
    const { records, settings } = load();
    const sorted = [...records].sort((a, b) => a.month.localeCompare(b.month));
    const last = sorted.at(-1);
    const now = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 7); // 北京时间的当前月
    return send(res, 200, {
      savings: {
        records: records.length,
        latest: last?.month || null,
        // 当前存款 = 定存 + 个人存储 + 公积金余额（与 App、网页同一口径）
        balance: sorted.reduce((s, r) => s + r.fixed + r.personal, 0) + fundBalanceOf(sorted, settings),
        latestSaved: last ? last.fixed + last.personal - last.fundWithdrawal : 0,
        goal: settings.goal,
        currentMonth: now,
        currentRecorded: sorted.some((r) => r.month === now),
      },
      codex: codex.status === "fulfilled" ? codex.value : { sessions: [], machines: hub.machineList(), errors: [{ error: codex.reason.message }] },
      daily: daily.status === "fulfilled" ? daily.value : null,
    });
  }

  if (path.startsWith("/api/savings/")) {
    const out = await savingsRoute(method, path.slice("/api/savings".length), () => readJson(req));
    if (!out) return send(res, 404, { error: "接口不存在" });
    if (out[0] === "export") return sendExport(res, out[1]);
    return send(res, out[0], out[1]);
  }

  if (path === "/api/news/daily" && method === "GET") {
    const date = new URL(req.url, "http://x").searchParams.get("date");
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) return send(res, 400, { error: "日期格式应为 YYYY-MM-DD" });
    return send(res, 200, await news.daily(date));
  }
  if (path === "/api/news/hot" && method === "GET") return send(res, 200, await news.hot());
  if (path === "/api/news/img" && method === "GET") {
    const img = await news.image(new URL(req.url, "http://x").searchParams.get("u"));
    res.writeHead(200, { "Content-Type": img.type, "Content-Length": img.body.length, "Cache-Control": "private, max-age=86400" });
    return res.end(img.body);
  }
  {
    const m = path.match(/^\/api\/news\/items\/([\w-]+)$/);
    if (m && method === "GET") return send(res, 200, await news.article(m[1]));
  }

  if (path === "/api/todos/parse" && method === "POST") {
    const { text } = await readJson(req);
    return send(res, 200, await parseTodo(text));
  }
  if (path === "/api/todos" || path.startsWith("/api/todos/")) {
    const out = await todosRoute(method, path.slice("/api/todos".length), () => readJson(req));
    if (!out) return send(res, 404, { error: "接口不存在" });
    if (method !== "GET") todosChanged();
    return send(res, out[0], out[1]);
  }

  if (path.startsWith("/api/codex/")) return codexApi(req, res, method, path.slice("/api/codex".length));

  return send(res, 404, { error: "接口不存在" });
}

const SEG = "([\\w.@-]+)";
async function codexApi(req, res, method, path) {
  let m;
  if (path === "/sessions" && method === "GET") return send(res, 200, await hub.sessions());
  if (path === "/machines" && method === "GET") return send(res, 200, { machines: hub.machineList() });
  if ((m = path.match(new RegExp(`^/machines/${SEG}/dirs$`))) && method === "GET") return send(res, 200, { dirs: await hub.recentDirs(m[1]) });
  if ((m = path.match(new RegExp(`^/machines/${SEG}/image$`))) && method === "GET") {
    const img = await hub.machineImage(m[1], new URL(req.url, "http://x").searchParams.get("path"));
    res.writeHead(200, { "Content-Type": img.type, "Content-Length": img.bytes.length, "Cache-Control": "private, max-age=86400" });
    return res.end(img.bytes);
  }
  if ((m = path.match(new RegExp(`^/sessions/${SEG}/${SEG}$`))) && method === "GET") {
    const q = new URL(req.url, "http://x").searchParams;
    const limit = Math.min(Math.max(Number(q.get("limit")) || 3, 1), 20);
    return send(res, 200, await hub.session(m[1], m[2], { limit }));
  }
  if ((m = path.match(new RegExp(`^/sessions/${SEG}/${SEG}/older$`))) && method === "GET") {
    const before = new URL(req.url, "http://x").searchParams.get("before");
    if (!before) throw new InputError("缺少 before");
    return send(res, 200, await hub.olderTurns(m[1], m[2], before));
  }
  if ((m = path.match(new RegExp(`^/sessions/${SEG}/${SEG}/message$`))) && method === "POST") {
    const { text, images } = await readJson(req, BIG_BODY);
    if (!String(text || "").trim() && !images?.length) throw new InputError("消息不能为空");
    return send(res, 200, await hub.message(m[1], m[2], String(text || "").slice(0, 20_000), images || []));
  }
  if ((m = path.match(new RegExp(`^/sessions/${SEG}/${SEG}/stop$`))) && method === "POST") return send(res, 200, await hub.stop(m[1], m[2]));
  if ((m = path.match(new RegExp(`^/sessions/${SEG}/${SEG}/images/${SEG}/(\\d+)$`))) && method === "GET") {
    const img = await hub.image(m[1], m[2], m[3], Number(m[4]));
    res.writeHead(200, { "Content-Type": img.type, "Content-Length": img.bytes.length, "Cache-Control": "private, max-age=86400" });
    return res.end(img.bytes);
  }
  if ((m = path.match(new RegExp(`^/approvals/${SEG}/${SEG}$`))) && method === "POST") {
    const { decision } = await readJson(req);
    return send(res, 200, hub.approve(m[1], m[2], decision));
  }
  if ((m = path.match(new RegExp(`^/sessions/${SEG}$`))) && method === "POST") {
    const { cwd, text, images } = await readJson(req, BIG_BODY);
    if (!cwd || (!String(text || "").trim() && !images?.length)) throw new InputError("需要目录和第一条消息");
    return send(res, 201, await hub.start(m[1], String(cwd), String(text || "").slice(0, 20_000), images || []));
  }
  return send(res, 404, { error: "接口不存在" });
}

/* ---------- 启动 ---------- */

const server = createServer(async (req, res) => {
  const path = new URL(req.url || "/", "http://local").pathname;
  try {
    // 带设备凭证的是 App / uTools，否则是网页（Cookie 登录）
    if (path.startsWith("/api/")) {
      const app = req.headers.authorization || path === "/api/pair" || path === "/api/version";
      return await (app ? appApi(req, res, path) : webApi(req, res, path));
    }
    if (path.startsWith("/download/")) return serveDownload(req, res, path.slice("/download/".length));
    return serveStatic(res, LEDGER_DIST, path);
  } catch (error) {
    if (error instanceof InputError || error instanceof TodoError || error.status) return send(res, error.status || 400, { error: error.message });
    console.error("[me]", error);
    send(res, 500, { error: error.message || "服务器错误" });
  }
});

server.on("upgrade", (req, socket, head) => {
  if (alertsUpgrade(req, socket, head)) return;
  if (asr.asrUpgrade(req, socket, head, deviceOk)) return;
  if (!hub.handleUpgrade(req, socket, head, { deviceOk })) socket.destroy();
});

server.listen(PORT, HOST, () => {
  console.log(`[me] listening on http://${HOST}:${PORT} (${load().records.length} savings records)`);
  asr.warm();
});
