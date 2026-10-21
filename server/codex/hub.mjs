// Codex 中转（ADR 0002 / 0005）。
//
// 每台机器上的 agentdeck 程序主动用 WebSocket 连到这里（/codex/machine），并把本机 Codex 后台服务
// （app-server 的控制 socket）的 JSON-RPC 消息原样双向转发。中转在服务器侧完成「做客户端」的工作：
// initialize、thread/list、thread/read、turn/start……再把结果整理成 App 用的精简格式。
//
// App 侧：
//   GET  /api/codex/sessions                      所有机器的会话（含「等你处理」）
//   GET  /api/codex/sessions/:machine/:id         某个会话的内容
//   POST /api/codex/sessions/:machine/:id/message { text }      发消息（空闲时开新一轮，运行中则插话）
//   POST /api/codex/sessions/:machine/:id/stop
//   POST /api/codex/approvals/:machine/:key       { decision: "accept" | "decline" }
//   POST /api/codex/sessions/:machine             { cwd, text }  新开会话
//   GET  /api/codex/machines                      机器列表与最近目录
//   WS   /api/codex/live                          推送 {type:"changed", machine, sessionId}
import { createHash, timingSafeEqual } from "node:crypto";
import { WebSocketServer } from "ws";
import { diffStates, finishedAlert, waitingAlert } from "../alerts.mjs";
import { summarizeThread, summarizeTurns, userImage } from "./shape.mjs";

const RPC_TIMEOUT = 20_000;
const sha = (s) => createHash("sha256").update(s).digest("hex");

const MAX_IMAGES = 6;
const MAX_IMAGE_BYTES = 4_000_000; // 单张 data URL 的长度上限（手机端会先压缩到 1600px）
const IMAGE_URL = /^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+$/;

/** 文字 + 图片（data URL）→ Codex 的 UserInput 列表 */
export function buildInput(text, images = []) {
  if (!Array.isArray(images)) throw Object.assign(new Error("images 应为数组"), { status: 400 });
  if (images.length > MAX_IMAGES) throw Object.assign(new Error(`一次最多 ${MAX_IMAGES} 张图片`), { status: 400 });
  const input = [];
  if (String(text || "").trim()) input.push({ type: "text", text: String(text), text_elements: [] });
  for (const url of images) {
    if (typeof url !== "string" || !IMAGE_URL.test(url)) throw Object.assign(new Error("图片格式不支持"), { status: 400 });
    if (url.length > MAX_IMAGE_BYTES) throw Object.assign(new Error("图片太大"), { status: 413 });
    input.push({ type: "image", url });
  }
  if (!input.length) throw Object.assign(new Error("消息不能为空"), { status: 400 });
  return input;
}

/** 机器令牌：MACHINE_TOKENS="name:sha256hex,name2:sha256hex" */
function machineFromToken(token) {
  if (!token) return null;
  const hash = sha(token);
  for (const pair of String(process.env.ME_MACHINE_TOKENS || "").split(",")) {
    const [name, h] = pair.trim().split(":");
    if (name && h && h.length === hash.length && timingSafeEqual(Buffer.from(h), Buffer.from(hash))) return name;
  }
  return null;
}

class Machine {
  constructor(name, ws, hub) {
    this.name = name;
    this.ws = ws;
    this.hub = hub;
    this.nextId = 1;
    this.pending = new Map();
    this.approvals = new Map(); // key -> { rpcId, threadId, kind, command, cwd, reason, at }
    this.ready = this.init();
    this.connectedAt = Date.now();
    this.threads = new Map(); // 缓存最近一次的列表结果
  }

  async init() {
    await this.call("initialize", {
      clientInfo: { name: "me", title: "我", version: "0.1.0" },
      capabilities: { experimentalApi: true },
    });
    this.notify("initialized");
  }

  send(obj) {
    if (this.ws.readyState === 1) this.ws.send(JSON.stringify(obj));
  }
  notify(method, params) {
    this.send(params === undefined ? { method } : { method, params });
  }
  call(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${this.name} 上的 Codex 没有响应（${method}）`));
      }, RPC_TIMEOUT);
      this.pending.set(id, { resolve, reject, timer });
      this.send({ id, method, params });
    });
  }
  async rpc(method, params) {
    await this.ready;
    return this.call(method, params);
  }

  onMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    // 对我们请求的响应
    if (msg.id !== undefined && msg.method === undefined) {
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      clearTimeout(p.timer);
      if (msg.error) p.reject(new Error(msg.error.message || "Codex 返回错误"));
      else p.resolve(msg.result);
      return;
    }
    // Codex 向客户端发起的请求：审批
    if (msg.id !== undefined && msg.method) {
      this.onServerRequest(msg);
      return;
    }
    // 通知
    const threadId = msg.params?.threadId || msg.params?.thread?.id || msg.params?.turn?.threadId;
    if (msg.method === "serverRequest/resolved") {
      for (const [key, a] of this.approvals) if (String(a.rpcId) === String(msg.params?.requestId)) this.approvals.delete(key);
    }
    if (threadId && INTERESTING.has(msg.method)) this.hub.changed(this.name, threadId);
  }

  onServerRequest(msg) {
    const p = msg.params || {};
    const kinds = {
      "item/commandExecution/requestApproval": "command",
      "execCommandApproval": "command",
      "item/fileChange/requestApproval": "fileChange",
      "applyPatchApproval": "fileChange",
      "item/permissions/requestApproval": "permissions",
    };
    const kind = kinds[msg.method];
    if (!kind) {
      // 不支持的请求不在这里回应，桌面端等其他客户端还能处理
      return;
    }
    const key = `${msg.id}`;
    this.approvals.set(key, {
      key,
      rpcId: msg.id,
      method: msg.method,
      threadId: p.threadId || p.conversationId,
      kind,
      command: Array.isArray(p.command) ? p.command.join(" ") : p.command || null,
      cwd: p.cwd || null,
      reason: p.reason || null,
      at: Date.now(),
    });
    this.hub.changed(this.name, p.threadId || p.conversationId);
  }

  answerApproval(key, decision) {
    const a = this.approvals.get(key);
    if (!a) return false;
    const legacy = a.method === "execCommandApproval" || a.method === "applyPatchApproval";
    const value = legacy ? (decision === "accept" ? "approved" : "denied") : decision;
    this.send({ id: a.rpcId, result: { decision: value } });
    this.approvals.delete(key);
    this.hub.changed(this.name, a.threadId);
    return true;
  }

  close() {
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new Error(`${this.name} 已断开`));
    }
    this.pending.clear();
  }
}

const INTERESTING = new Set([
  "thread/started", "thread/status/changed", "thread/name/updated", "turn/started", "turn/completed",
  "item/started", "item/completed", "turn/plan/updated", "thread/closed",
]);

export class CodexHub {
  constructor(alerts = null) {
    this.machines = new Map();
    this.lastSeen = new Map();
    this.listeners = new Set();
    this.pendingChanges = new Map();
    this.alerts = alerts;
    this.states = new Map(); // machine -> Map(sessionId -> summary)，用来比出「完成 / 出错 / 等你」
    this.scanTimers = new Map();
    this.machineWss = new WebSocketServer({ noServer: true, maxPayload: 32 * 1024 * 1024 });
    this.liveWss = new WebSocketServer({ noServer: true });
    // 兜底：桌面端开的会话不一定把事件发给我们，定时对一遍状态
    if (alerts) setInterval(() => { for (const name of this.machines.keys()) this.scan(name); }, 30_000).unref();
  }

  /** 在 http server 的 upgrade 事件里调用；返回是否已处理 */
  handleUpgrade(req, socket, head, { deviceOk }) {
    const url = new URL(req.url, "http://x");
    if (url.pathname === "/codex/machine") {
      const token = String(req.headers.authorization || "").replace(/^Bearer\s+/i, "");
      const name = machineFromToken(token);
      if (!name) {
        socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
        return true;
      }
      this.machineWss.handleUpgrade(req, socket, head, (ws) => this.attach(name, ws));
      return true;
    }
    if (url.pathname === "/api/codex/live") {
      if (!deviceOk(req)) {
        socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
        return true;
      }
      this.liveWss.handleUpgrade(req, socket, head, (ws) => {
        this.listeners.add(ws);
        ws.on("close", () => this.listeners.delete(ws));
        ws.send(JSON.stringify({ type: "hello", machines: this.onlineNames() }));
      });
      return true;
    }
    return false;
  }

  attach(name, ws) {
    const old = this.machines.get(name);
    if (old) {
      old.close();
      old.ws.terminate();
    }
    const m = new Machine(name, ws, this);
    this.machines.set(name, m);
    console.log(`[codex] ${name} 已连接`);
    ws.on("message", (data) => m.onMessage(String(data)));
    ws.on("close", () => {
      if (this.machines.get(name) === m) this.machines.delete(name);
      this.lastSeen.set(name, Date.now());
      this.states.delete(name); // 重连后重新建基线，断线期间的变化不补发
      m.close();
      console.log(`[codex] ${name} 已断开`);
      this.broadcast({ type: "machines", machines: this.onlineNames() });
    });
    // 保活
    const ping = setInterval(() => (ws.readyState === 1 ? ws.ping() : clearInterval(ping)), 25_000);
    m.ready.then(
      () => {
        this.broadcast({ type: "machines", machines: this.onlineNames() });
        this.scan(name);
      },
      (err) => console.warn(`[codex] ${name} 初始化失败：${err.message}`),
    );
  }

  onlineNames() {
    return [...this.machines.keys()];
  }

  changed(machine, sessionId) {
    // 合并 300ms 内的重复变更，避免流式输出时刷屏
    const key = `${machine}/${sessionId}`;
    if (this.pendingChanges.has(key)) return;
    this.pendingChanges.set(key, setTimeout(() => {
      this.pendingChanges.delete(key);
      this.broadcast({ type: "changed", machine, sessionId });
    }, 300));
    this.scanSoon(machine);
  }

  /* ---------- 提醒 ---------- */

  scanSoon(machine) {
    if (!this.alerts || this.scanTimers.has(machine)) return;
    this.scanTimers.set(machine, setTimeout(() => {
      this.scanTimers.delete(machine);
      this.scan(machine);
    }, 1500));
  }

  async scan(name) {
    const m = this.machines.get(name);
    if (!this.alerts || !m || m.scanning) return;
    m.scanning = true;
    try {
      const res = await m.rpc("thread/list", { limit: 40, sortKey: "updated_at" });
      const next = new Map();
      for (const t of res.data || []) {
        if (t.ephemeral || t.parentThreadId) continue;
        next.set(t.id, summarizeThread(name, t, [...m.approvals.values()].filter((a) => a.threadId === t.id)));
      }
      const prev = this.states.get(name) || null;
      // 列表只取了最近 40 个，旧基线里有、这次没出现的保留着，避免它下次出现时被当成新变化
      if (prev) for (const [id, s] of prev) if (!next.has(id)) next.set(id, s);
      this.states.set(name, next);
      for (const { kind, session } of diffStates(prev, next)) {
        if (kind === "waiting") this.alerts.push(waitingAlert(session));
        else if (kind === "resolved") this.alerts.push({ kind: "resolved", machine: name, sessionId: session.id, name: session.name });
        else {
          const turn = await this.lastTurn(m, session.id, "full").catch(() => null);
          this.alerts.push(kind === "failed" && !turn ? { ...finishedAlert(session, null), kind: "failed" } : finishedAlert(session, turn));
        }
      }
    } catch (e) {
      // 机器忙或刚断开，下次再对
    } finally {
      m.scanning = false;
    }
  }

  broadcast(obj) {
    const text = JSON.stringify(obj);
    for (const ws of this.listeners) if (ws.readyState === 1) ws.send(text);
  }

  machine(name) {
    const m = this.machines.get(name);
    if (!m) throw Object.assign(new Error(`${name} 当前不在线`), { status: 409 });
    return m;
  }

  async sessions() {
    const since = Date.now() / 1000 - 7 * 86400;
    const results = await Promise.allSettled(
      [...this.machines.values()].map(async (m) => {
        const res = await m.rpc("thread/list", { limit: 40, sortKey: "updated_at" });
        return (res.data || [])
          .filter((t) => !t.ephemeral && !t.parentThreadId)
          .map((t) => summarizeThread(m.name, t, [...m.approvals.values()].filter((a) => a.threadId === t.id)));
      }),
    );
    // 同一个会话可能在列表里出现多次（如列表翻页时更新），按「机器/会话」去重，保留最新的一条
    const byKey = new Map();
    for (const s of results.flatMap((r) => (r.status === "fulfilled" ? r.value : []))) {
      const k = `${s.machine}/${s.id}`;
      const old = byKey.get(k);
      if (!old || s.updatedAt > old.updatedAt) byKey.set(k, s);
    }
    const all = [...byKey.values()];
    const errors = results.flatMap((r, i) => (r.status === "rejected" ? [{ machine: [...this.machines.keys()][i], error: r.reason.message }] : []));
    const rank = (s) => (s.state === "waiting" ? 0 : s.state === "running" ? 1 : 2);
    all.sort((a, b) => rank(a) - rank(b) || b.updatedAt - a.updatedAt);
    return {
      sessions: all.filter((s) => s.state !== "done" || s.updatedAt >= since),
      older: all.filter((s) => s.state === "done" && s.updatedAt < since).length,
      machines: this.machineList(),
      errors,
    };
  }

  machineList() {
    const known = String(process.env.ME_MACHINE_TOKENS || "").split(",").map((p) => p.split(":")[0].trim()).filter(Boolean);
    return known.map((name) => ({
      name,
      online: this.machines.has(name),
      lastSeenAt: this.machines.has(name) ? Date.now() : this.lastSeen.get(name) || null,
    }));
  }

  /** limit：首屏只取最近几轮；更早的用 before（上一页给的游标）往前翻 */
  async session(machine, id, { limit = 3 } = {}) {
    const m = this.machine(machine);
    const [res, turns] = await Promise.all([
      m.rpc("thread/read", { threadId: id, includeTurns: false }),
      m.rpc("thread/turns/list", { threadId: id, limit, itemsView: "full", sortDirection: "desc" }),
    ]);
    const list = [...(turns.data || [])].reverse();
    const approvals = [...m.approvals.values()].filter((a) => a.threadId === id);
    return {
      ...summarizeThread(machine, { ...res.thread, turns: list }, approvals),
      turns: summarizeTurns(list),
      moreTurns: Boolean(turns.nextCursor),
      before: turns.nextCursor || null,
    };
  }

  /** 更早的几轮 */
  async olderTurns(machine, id, before, limit = 4) {
    const m = this.machine(machine);
    const turns = await m.rpc("thread/turns/list", { threadId: id, limit, cursor: before, itemsView: "full", sortDirection: "desc" });
    const list = [...(turns.data || [])].reverse();
    return { turns: summarizeTurns(list), before: turns.nextCursor || null };
  }

  async lastTurn(m, id, itemsView = "notLoaded") {
    const res = await m.rpc("thread/turns/list", { threadId: id, limit: 1, itemsView, sortDirection: "desc" });
    return (res.data || [])[0] || null;
  }

  /** 会话里用户发过的某张图片 → { type, bytes } */
  /** 回复里引用的本机图片（比如 Codex 生成的二维码、截图）：请那台机器读出来。只放行图片 */
  async machineImage(machine, path) {
    const p = String(path || "");
    const ext = p.split(".").pop().toLowerCase();
    const type = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif" }[ext];
    if (!p.startsWith("/") || p.includes("\0") || !type) throw Object.assign(new Error("只能读取图片文件"), { status: 400 });
    const m = this.machine(machine);
    const file = await m.rpc("fs/readFile", { path: p });
    const bytes = Buffer.from(file.dataBase64 || "", "base64");
    if (!bytes.length) throw Object.assign(new Error("图片不存在"), { status: 404 });
    if (bytes.length > 15 * 1024 * 1024) throw Object.assign(new Error("图片太大"), { status: 413 });
    return { type, bytes };
  }

  async image(machine, id, itemId, index) {
    const m = this.machine(machine);
    // 图片只会出现在最近几轮里（手机上也只显示最近 6 轮），翻 20 轮足够
    const res = await m.rpc("thread/turns/list", { threadId: id, limit: 20, itemsView: "full", sortDirection: "desc" });
    const ref = userImage(res.data || [], itemId, index);
    if (!ref) throw Object.assign(new Error("图片不存在"), { status: 404 });
    if (ref.url) {
      const mm = String(ref.url).match(/^data:(image\/[\w+.-]+);base64,(.*)$/s);
      if (!mm) throw Object.assign(new Error("图片格式不支持"), { status: 415 });
      return { type: mm[1], bytes: Buffer.from(mm[2], "base64") };
    }
    // 电脑上发的截图是那台机器上的本地文件，请那台机器的 Codex 读出来
    const file = await m.rpc("fs/readFile", { path: ref.path });
    const ext = String(ref.path).split(".").pop().toLowerCase();
    const type = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif" }[ext] || "image/png";
    return { type, bytes: Buffer.from(file.dataBase64 || "", "base64") };
  }

  async ensureLoaded(m, id) {
    // 让后台服务把会话加载起来并订阅它的事件（会话可能是桌面端开的，已加载时这是无害的重新加入）
    const res = await m.rpc("thread/resume", { threadId: id, excludeTurns: true });
    return res.thread || res;
  }

  async message(machine, id, text, images = []) {
    const m = this.machine(machine);
    const thread = await this.ensureLoaded(m, id);
    const input = buildInput(text, images);
    const last = await this.lastTurn(m, id);
    if (last && last.status === "inProgress") {
      await m.rpc("turn/steer", { threadId: id, expectedTurnId: last.id, input });
      return { mode: "steer" };
    }
    await m.rpc("turn/start", { threadId: id, input });
    this.changed(machine, thread.id || id);
    return { mode: "start" };
  }

  async stop(machine, id) {
    const m = this.machine(machine);
    await this.ensureLoaded(m, id);
    const last = await this.lastTurn(m, id);
    if (!last || last.status !== "inProgress") return { stopped: false };
    await m.rpc("turn/interrupt", { threadId: id, turnId: last.id });
    return { stopped: true };
  }

  approve(machine, key, decision) {
    const m = this.machine(machine);
    if (!["accept", "decline"].includes(decision)) throw Object.assign(new Error("decision 只能是 accept 或 decline"), { status: 400 });
    if (!m.answerApproval(key, decision)) throw Object.assign(new Error("这个请求已经被处理了"), { status: 409 });
    return { ok: true };
  }

  async start(machine, cwd, text, images = []) {
    const m = this.machine(machine);
    const res = await m.rpc("thread/start", { cwd });
    const id = res.thread?.id;
    await m.rpc("turn/start", { threadId: id, input: buildInput(text, images) });
    return { id };
  }

  async recentDirs(machine) {
    const m = this.machine(machine);
    const res = await m.rpc("thread/list", { limit: 60, sortKey: "updated_at" });
    const seen = new Map();
    for (const t of res.data || []) if (t.cwd && !seen.has(t.cwd)) seen.set(t.cwd, t.updatedAt);
    return [...seen.keys()].slice(0, 12);
  }
}
