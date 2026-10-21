// 手机提醒（ADR 0007）：服务器把「Codex 完成 / 出错 / 等你批准」记成一条条提醒，
// App 的常驻连接（/api/alerts/live）实时收；断线重连时带上收到的最后一条序号，补发期间漏掉的。
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const FILE = process.env.ME_ALERTS_FILE || "/var/lib/me/alerts.json";
const KEEP = 200;
const REPLAY_MS = 12 * 3600_000; // 断线太久的就不补了，免得一次弹一堆过期提醒

function load(file) {
  try {
    return existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : [];
  } catch {
    return [];
  }
}

export class Alerts {
  constructor(file = FILE) {
    this.file = file;
    this.items = file ? load(file) : [];
    this.last = this.items.at(-1)?.seq || 0;
    this.listeners = new Set();
  }

  /** a: { kind: done|interrupted|failed|waiting|resolved, machine, sessionId, name, text, approvalKey? } */
  push(a) {
    // 序号用毫秒时间戳，服务重启后仍然递增
    const seq = Math.max(Date.now(), this.last + 1);
    this.last = seq;
    const item = { seq, at: Date.now(), ...a };
    this.items.push(item);
    if (this.items.length > KEEP) this.items.splice(0, this.items.length - KEEP);
    this.save();
    for (const fn of this.listeners) {
      try {
        fn(item);
      } catch {}
    }
    return item;
  }

  /** seq 之后、且不太旧的提醒；seq 为 0（App 第一次连）时不补发历史 */
  since(seq) {
    if (!seq) return [];
    const after = Date.now() - REPLAY_MS;
    return this.items.filter((i) => i.seq > seq && i.at > after);
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  save() {
    if (!this.file) return;
    try {
      mkdirSync(dirname(this.file), { recursive: true, mode: 0o700 });
      writeFileSync(`${this.file}.tmp`, JSON.stringify(this.items), { mode: 0o600 });
      renameSync(`${this.file}.tmp`, this.file);
    } catch (e) {
      console.warn("[alerts] 保存失败：", e.message);
    }
  }
}

export const clip = (s, n) => {
  const t = String(s || "").replace(/\s+/g, " ").trim();
  return t.length > n ? `${t.slice(0, n)}…` : t;
};

/**
 * 比较同一台机器前后两次的会话状态，得出要发的提醒。
 * 用「状态变化」判断而不只靠事件：桌面端开的会话，事件不一定发到我们这条连接，但列表里的状态总是准的。
 * prev 为 null 表示第一次看到这台机器，只记基线不提醒。
 */
export function diffStates(prev, next) {
  const out = [];
  if (!prev) return out;
  for (const [id, s] of next) {
    const before = prev.get(id)?.state;
    const keyBefore = prev.get(id)?.approvals?.[0]?.key;
    const keyNow = s.approvals?.[0]?.key;
    if (before === s.state && keyBefore === keyNow) continue;
    if (before === "waiting" && s.state !== "waiting") out.push({ kind: "resolved", session: s });
    if (s.state === "waiting" && (before !== "waiting" || keyBefore !== keyNow)) out.push({ kind: "waiting", session: s });
    else if (s.state === "error" && before !== "error") out.push({ kind: "failed", session: s });
    else if (s.state === "done" && (before === "running" || before === "waiting")) out.push({ kind: "finished", session: s });
  }
  return out;
}

/** 由最后一轮的结果生成完成类提醒 */
export function finishedAlert(session, turn) {
  const status = turn?.status;
  const kind = status === "failed" ? "failed" : status === "interrupted" ? "interrupted" : "done";
  let text = "";
  if (kind === "failed") text = turn?.error?.message || "运行出错";
  else {
    const items = turn?.items || [];
    for (let i = items.length - 1; i >= 0; i--) {
      if (items[i].type === "agentMessage" && items[i].text) {
        text = items[i].text;
        break;
      }
    }
  }
  return { kind, machine: session.machine, sessionId: session.id, name: session.name, text: clip(text || session.lastMessage, 300) };
}

export function waitingAlert(session) {
  const a = session.approvals?.[0];
  const what = a ? (a.kind === "command" ? a.command : a.kind === "fileChange" ? "修改文件" : "申请权限") : "等你回复";
  return {
    kind: "waiting",
    machine: session.machine,
    sessionId: session.id,
    name: session.name,
    text: clip(a?.reason ? `${what} · ${a.reason}` : what, 300),
    approvalKey: a?.key || null,
  };
}
