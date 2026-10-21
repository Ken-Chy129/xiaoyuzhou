// 待办 / 备忘：一份 JSON 文件，只有本人一个设备在写，原子替换即可。
//
//   GET    /api/todos                 全部条目
//   POST   /api/todos                 新建 { kind, text, due, repeat, pinned, link }
//   PATCH  /api/todos/:id             修改任意字段
//   POST   /api/todos/:id/done        完成；重复事项则跳到下一次
//   POST   /api/todos/:id/undone      取消完成
//   DELETE /api/todos/:id
//
// 时间一律用北京时间的本地字符串：全天 "2026-10-20"，定时 "2026-10-20T15:00"。
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export const TODOS_FILE = process.env.ME_TODOS_FILE || "/var/lib/me/todos.json";

export class TodoError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

const KINDS = new Set(["todo", "note"]);
const UNITS = new Set(["day", "week", "month", "year"]);
const LINKS = new Set(["savings/new"]);
const DUE = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?$/;

let cache = null;

function read() {
  if (cache) return cache;
  cache = existsSync(TODOS_FILE) ? JSON.parse(readFileSync(TODOS_FILE, "utf8")) : { version: 1, items: [] };
  return cache;
}

function write(data) {
  mkdirSync(dirname(TODOS_FILE), { recursive: true, mode: 0o700 });
  const tmp = `${TODOS_FILE}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 });
  renameSync(tmp, TODOS_FILE);
  cache = data;
}

/** 测试用：换数据文件后清掉缓存 */
export function _reset() {
  cache = null;
}

/* ---------- 日期 ---------- */

const pad = (n) => String(n).padStart(2, "0");
const daysIn = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate(); // m: 1-12

/** 北京时间的「现在」：{ date: "YYYY-MM-DD", stamp: "YYYY-MM-DDTHH:mm" } */
export function nowLocal(ms = Date.now()) {
  const iso = new Date(ms + 8 * 3600_000).toISOString();
  return { date: iso.slice(0, 10), stamp: iso.slice(0, 16) };
}

function parseDue(due) {
  const m = DUE.exec(due);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (mo < 1 || mo > 12 || d < 1 || d > daysIn(y, mo)) return null;
  if (m[4] != null && (Number(m[4]) > 23 || Number(m[5]) > 59)) return null;
  return { y, mo, d, time: m[4] != null ? `${m[4]}:${m[5]}` : null };
}

function formatDue({ y, mo, d, time }) {
  return `${y}-${pad(mo)}-${pad(d)}` + (time ? `T${time}` : "");
}

function addDays({ y, mo, d, time }, n) {
  const t = new Date(Date.UTC(y, mo - 1, d + n));
  return { y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate(), time };
}

/** 下一次：按月/年时以 repeat.day（锚定日）为准，31 号在小月取月末，下个月再回到 31 号 */
function step(p, repeat) {
  const every = repeat.every || 1;
  switch (repeat.unit) {
    case "day":
      return addDays(p, every);
    case "week":
      return addDays(p, 7 * every);
    case "month": {
      const total = p.y * 12 + (p.mo - 1) + every;
      const y = Math.floor(total / 12);
      const mo = (total % 12) + 1;
      return { y, mo, d: Math.min(repeat.day || p.d, daysIn(y, mo)), time: p.time };
    }
    case "year": {
      const y = p.y + every;
      const mo = repeat.month || p.mo;
      return { y, mo, d: Math.min(repeat.day || p.d, daysIn(y, mo)), time: p.time };
    }
    default:
      throw new TodoError("重复规则不正确");
  }
}

/** 新建/修改重复事项时：给的首次时间已经过去，就落到之后最近的一次（「每月 18 号」在 19 号建，从下个月 18 号开始） */
export function firstDue(due, repeat, ms = Date.now()) {
  let p = parseDue(due);
  if (!p) return due;
  const now = nowLocal(ms);
  const passed = (q) => (q.time ? formatDue(q) <= now.stamp : formatDue(q) < now.date);
  let guard = 0;
  while (passed(p) && ++guard < 5000) p = step(p, repeat);
  return formatDue(p);
}

/** 完成一次重复事项后的下一个到期时间：至少前进一次，并且跳过已经过去的 */
export function nextDue(due, repeat, ms = Date.now()) {
  let p = parseDue(due);
  if (!p) throw new TodoError("到期时间格式不正确");
  const now = nowLocal(ms);
  const passed = (q) => (q.time ? formatDue(q) <= now.stamp : formatDue(q) <= now.date);
  let guard = 0;
  do {
    p = step(p, repeat);
    if (++guard > 5000) break;
  } while (passed(p));
  return formatDue(p);
}

/* ---------- 校验 ---------- */

function clean(input, base = {}) {
  const out = { ...base };
  if ("kind" in input) {
    if (!KINDS.has(input.kind)) throw new TodoError("类型只能是 todo 或 note");
    out.kind = input.kind;
  }
  if ("text" in input) {
    const text = String(input.text ?? "").trim();
    if (!text) throw new TodoError("内容不能为空");
    if (text.length > 4000) throw new TodoError("内容太长");
    out.text = text;
  }
  if ("pinned" in input) out.pinned = Boolean(input.pinned);
  if ("due" in input) {
    if (input.due == null || input.due === "") out.due = null;
    else {
      const p = parseDue(String(input.due));
      if (!p) throw new TodoError("到期时间格式应为 YYYY-MM-DD 或 YYYY-MM-DDTHH:mm");
      out.due = formatDue(p);
    }
  }
  if ("repeat" in input) {
    const r = input.repeat;
    if (r == null) out.repeat = null;
    else {
      if (!UNITS.has(r.unit)) throw new TodoError("重复单位只能是 day / week / month / year");
      const every = r.every == null ? 1 : Number(r.every);
      if (!Number.isInteger(every) || every < 1 || every > 99) throw new TodoError("重复间隔不正确");
      const rep = { unit: r.unit, every };
      if (r.day != null) {
        const day = Number(r.day);
        if (!Number.isInteger(day) || day < 1 || day > 31) throw new TodoError("重复日期不正确");
        rep.day = day;
      }
      if (r.month != null) {
        const month = Number(r.month);
        if (!Number.isInteger(month) || month < 1 || month > 12) throw new TodoError("重复月份不正确");
        rep.month = month;
      }
      out.repeat = rep;
    }
  }
  if ("link" in input) {
    if (input.link != null && !LINKS.has(input.link)) throw new TodoError("不支持的跳转");
    out.link = input.link ?? null;
  }
  if (out.repeat && !out.due) throw new TodoError("重复事项需要一个到期时间");
  if (out.kind === "note") {
    out.repeat = null;
    out.done = false;
  }
  // 按月重复没给锚定日时，用到期日那天
  if (out.repeat && (out.repeat.unit === "month" || out.repeat.unit === "year") && !out.repeat.day) {
    out.repeat = { ...out.repeat, day: parseDue(out.due).d };
  }
  if (out.repeat?.unit === "year" && !out.repeat.month) out.repeat = { ...out.repeat, month: parseDue(out.due).mo };
  if (out.repeat && out.due && ("due" in input || "repeat" in input)) out.due = firstDue(out.due, out.repeat);
  return out;
}

/* ---------- 操作 ---------- */

export function list() {
  return { items: read().items };
}

export function create(input) {
  const now = Date.now();
  const item = clean(
    { kind: "todo", pinned: false, due: null, repeat: null, link: null, ...input },
    { id: randomUUID().slice(0, 12), done: false, doneAt: null, createdAt: now, updatedAt: now },
  );
  if (!item.text) throw new TodoError("内容不能为空");
  const data = read();
  write({ ...data, items: [...data.items, item] });
  return item;
}

function find(id) {
  const data = read();
  const item = data.items.find((t) => t.id === id);
  if (!item) throw new TodoError("条目不存在", 404);
  return { data, item };
}

function replace(data, item) {
  write({ ...data, items: data.items.map((t) => (t.id === item.id ? item : t)) });
  return item;
}

export function update(id, input) {
  const { data, item } = find(id);
  const allowed = Object.fromEntries(
    Object.entries(input || {}).filter(([k]) => ["kind", "text", "pinned", "due", "repeat", "link"].includes(k)),
  );
  return replace(data, { ...clean(allowed, item), updatedAt: Date.now() });
}

export function done(id, ms = Date.now()) {
  const { data, item } = find(id);
  if (item.kind === "note") throw new TodoError("备忘没有完成状态");
  if (item.repeat && item.due) {
    // 重复事项：记下这次完成，跳到下一次，保持未完成
    return replace(data, {
      ...item,
      due: nextDue(item.due, item.repeat, ms),
      lastDoneAt: ms,
      doneCount: (item.doneCount || 0) + 1,
      updatedAt: ms,
    });
  }
  return replace(data, { ...item, done: true, doneAt: ms, updatedAt: ms });
}

export function undone(id) {
  const { data, item } = find(id);
  return replace(data, { ...item, done: false, doneAt: null, updatedAt: Date.now() });
}

export function remove(id) {
  const { data } = find(id);
  write({ ...data, items: data.items.filter((t) => t.id !== id) });
  return { ok: true };
}

/** 路由：返回 [status, body]，不匹配返回 null */
export async function todosRoute(method, path, body) {
  let m;
  if (path === "" || path === "/") {
    if (method === "GET") return [200, list()];
    if (method === "POST") return [201, create(await body())];
  }
  if ((m = path.match(/^\/([\w-]+)$/))) {
    if (method === "PATCH") return [200, update(m[1], await body())];
    if (method === "DELETE") return [200, remove(m[1])];
  }
  if ((m = path.match(/^\/([\w-]+)\/done$/)) && method === "POST") return [200, done(m[1])];
  if ((m = path.match(/^\/([\w-]+)\/undone$/)) && method === "POST") return [200, undone(m[1])];
  return null;
}
