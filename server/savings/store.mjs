import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

export const DATA_FILE = resolve(process.env.LEDGER_DATA_FILE || "/var/lib/me/savings/ledger.json");
const BACKUP_DIR = join(dirname(DATA_FILE), "backups");
const KEEP_BACKUPS = 80;

export const NUMBER_FIELDS = [
  "salary", "bonus", "insurance", "tax", "other", "family", "fund", "fixed", "personal", "fundWithdrawal",
];

const DEFAULT_SETTINGS = {
  goal: 1_000_000,
  fundBalance: null, // { amount, asOf: "YYYY-MM" }：公积金实际余额，用来校准估算
  otherAssets: 0, // 利息、理财收益等月度记录里没体现的资产
};

const emptyLedger = () => ({ version: 1, records: [], expenses: [], settings: { ...DEFAULT_SETTINGS } });

let cache = null;

export function load() {
  if (cache) return cache;
  if (!existsSync(DATA_FILE)) return (cache = emptyLedger());
  const parsed = JSON.parse(readFileSync(DATA_FILE, "utf8"));
  cache = { ...emptyLedger(), ...parsed, settings: { ...DEFAULT_SETTINGS, ...(parsed.settings || {}) } };
  return cache;
}

function backup() {
  if (!existsSync(DATA_FILE)) return;
  mkdirSync(BACKUP_DIR, { recursive: true, mode: 0o700 });
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  writeFileSync(join(BACKUP_DIR, `ledger-${stamp}.json`), readFileSync(DATA_FILE), { mode: 0o600 });
  const files = readdirSync(BACKUP_DIR).filter((f) => f.startsWith("ledger-")).sort();
  for (const old of files.slice(0, Math.max(0, files.length - KEEP_BACKUPS))) unlinkSync(join(BACKUP_DIR, old));
}

export function save(next) {
  mkdirSync(dirname(DATA_FILE), { recursive: true, mode: 0o700 });
  backup();
  const tmp = `${DATA_FILE}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(next, null, 2), { mode: 0o600 });
  renameSync(tmp, DATA_FILE);
  cache = next;
  return next;
}

export class InputError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const round2 = (n) => Math.round(n * 100) / 100;

function num(value, label) {
  if (value === undefined || value === null || value === "") return 0;
  const n = Number(value);
  if (!Number.isFinite(n) || Math.abs(n) > 1e9) throw new InputError(`${label} 不是有效数字`);
  return round2(n);
}

const text = (value, max = 500) => (value === undefined || value === null ? "" : String(value).slice(0, max).trim());

const LABELS = {
  salary: "工资发放", bonus: "其中奖金", insurance: "五险一金", tax: "个税", other: "其他收入",
  family: "家庭补贴", fund: "公积金缴存", fixed: "定存", personal: "个人存储", fundWithdrawal: "公积金提取",
};

export function normalizeRecord(input, base = {}) {
  const merged = { ...base, ...input };
  if (!MONTH.test(String(merged.month || ""))) throw new InputError("月份格式应为 YYYY-MM");
  const record = { id: base.id || randomUUID(), month: merged.month };
  for (const field of NUMBER_FIELDS) {
    record[field] = num(merged[field], LABELS[field]);
    if (record[field] < 0) throw new InputError(`${LABELS[field]} 不能为负数`);
  }
  if (record.bonus > record.salary) throw new InputError("奖金不能超过工资发放");
  if (record.fundWithdrawal > record.fixed + record.personal) throw new InputError("公积金提取不能超过当月存入");
  record.special = Boolean(merged.special);
  record.note = text(merged.note);
  record.updatedAt = new Date().toISOString();
  if (base.source) record.source = base.source;
  return record;
}

export function normalizeExpense(input, base = {}) {
  const merged = { ...base, ...input };
  const name = text(merged.name, 40);
  if (!name) throw new InputError("请填写开销名称");
  if (!MONTH.test(String(merged.from || ""))) throw new InputError("开始月份格式应为 YYYY-MM");
  const to = merged.to ? String(merged.to) : null;
  if (to && (!MONTH.test(to) || to < merged.from)) throw new InputError("结束月份不能早于开始月份");
  const amount = num(merged.amount, "金额");
  if (amount <= 0) throw new InputError("金额需大于 0");
  return {
    id: base.id || randomUUID(),
    name,
    category: text(merged.category, 20) || "居住",
    amount,
    from: merged.from,
    to,
    note: text(merged.note, 200),
  };
}

export function normalizeSettings(input, base) {
  const next = { ...base };
  if ("goal" in input) {
    const goal = num(input.goal, "目标");
    if (goal <= 0) throw new InputError("目标需大于 0");
    next.goal = goal;
  }
  if ("otherAssets" in input) next.otherAssets = num(input.otherAssets, "其他资产");
  if ("fundBalance" in input) {
    const fb = input.fundBalance;
    if (!fb || fb.amount === "" || fb.amount === null || fb.amount === undefined) next.fundBalance = null;
    else {
      if (!MONTH.test(String(fb.asOf || ""))) throw new InputError("公积金余额的截至月份格式应为 YYYY-MM");
      next.fundBalance = { amount: num(fb.amount, "公积金余额"), asOf: fb.asOf };
    }
  }
  return next;
}
