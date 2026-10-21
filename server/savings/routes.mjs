// 储蓄模块的增删改。网站（Cookie 登录）和 App（设备凭证）共用这一份。
import { InputError, load, normalizeExpense, normalizeRecord, normalizeSettings, save } from "./store.mjs";

export function snapshot() {
  const data = load();
  return {
    records: [...data.records].sort((a, b) => a.month.localeCompare(b.month)),
    expenses: [...data.expenses].sort((a, b) => a.from.localeCompare(b.from)),
    settings: data.settings,
  };
}

function mutate(fn) {
  const next = structuredClone(load());
  fn(next);
  save(next);
  return snapshot();
}

/** 处理 /records /expenses /settings /data /export（前缀已去掉）。未匹配返回 undefined */
export async function savingsRoute(method, path, readJson) {
  if (path === "/data" && method === "GET") return [200, snapshot()];
  if (path === "/export" && method === "GET") return ["export", load()];

  if (path === "/records" && method === "POST") {
    const record = normalizeRecord(await readJson());
    if (load().records.some((r) => r.month === record.month)) throw new InputError(`${record.month} 已有记录`, 409);
    return [201, mutate((d) => d.records.push(record))];
  }
  const recordMatch = path.match(/^\/records\/([\w-]+)$/);
  if (recordMatch) {
    const id = recordMatch[1];
    const existing = load().records.find((r) => r.id === id);
    if (!existing) return [404, { error: "记录不存在" }];
    if (method === "PATCH") {
      const record = normalizeRecord(await readJson(), existing);
      if (load().records.some((r) => r.month === record.month && r.id !== id)) throw new InputError(`${record.month} 已有记录`, 409);
      return [200, mutate((d) => (d.records = d.records.map((r) => (r.id === id ? record : r))))];
    }
    if (method === "DELETE") return [200, mutate((d) => (d.records = d.records.filter((r) => r.id !== id)))];
  }

  if (path === "/expenses" && method === "POST") {
    const expense = normalizeExpense(await readJson());
    return [201, mutate((d) => d.expenses.push(expense))];
  }
  const expenseMatch = path.match(/^\/expenses\/([\w-]+)$/);
  if (expenseMatch) {
    const id = expenseMatch[1];
    const existing = load().expenses.find((e) => e.id === id);
    if (!existing) return [404, { error: "开销不存在" }];
    if (method === "PATCH") {
      const expense = normalizeExpense(await readJson(), existing);
      return [200, mutate((d) => (d.expenses = d.expenses.map((e) => (e.id === id ? expense : e))))];
    }
    if (method === "DELETE") return [200, mutate((d) => (d.expenses = d.expenses.filter((e) => e.id !== id)))];
  }

  if (path === "/settings" && method === "PUT") {
    const body = await readJson();
    return [200, mutate((d) => (d.settings = normalizeSettings(body, d.settings)))];
  }
  return undefined;
}
