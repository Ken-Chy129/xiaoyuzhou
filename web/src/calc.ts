import type { Entry, Expense, Settings } from "./types";

export type Mode = "fiscal" | "calendar";

export interface Row extends Entry {
  gross: number; // 税前收入 = 工资发放 + 其他收入
  baseSalary: number; // 工资发放 − 奖金
  takeHome: number; // 工资到手 = 工资 − 五险一金 − 个税
  disposable: number; // 可支配 = 工资到手 + 附加收入 − 家庭补贴
  fixedCost: number; // 固定开销（房租等）
  deposits: number; // 当月存入 = 定存 + 个人存储
  saved: number; // 收入存下 = 存入 − 公积金提取
  savedFixed: number;
  savedPersonal: number;
  leftover: number; // 其他花销（推算）= 可支配 − 固定开销 − 存下
  rate: number | null; // 当月储蓄率 = 存下 ÷ 可支配
  fundSelf: number; // 五险一金里的公积金个人部分 ≈ 公积金双边 ÷ 2
  social: number; // 社保 ≈ 五险一金 − 公积金个人部分
  balance: number; // 当前存款 = 定存 + 个人存储 + 公积金余额
  balanceFixed: number;
  balancePersonal: number;
  balanceFund: number; // 公积金余额 = 累计缴存 − 提取（可用实际余额校准）
}

export const monthIndex = (month: string) => Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1;
export const monthFromIndex = (index: number) =>
  `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
export const addMonths = (month: string, n: number) => monthFromIndex(monthIndex(month) + n);

export function fixedCostFor(month: string, expenses: Expense[]) {
  return expenses.reduce((sum, e) => (e.from <= month && (!e.to || month <= e.to) ? sum + e.amount : sum), 0);
}

export function derive(entries: Entry[], expenses: Expense[], settings?: Settings): Row[] {
  let balanceFixed = 0;
  let balancePersonal = 0;
  let fund = 0;
  const anchor = settings?.fundBalance;
  return [...entries]
    .sort((a, b) => a.month.localeCompare(b.month))
    .map((e) => {
      const takeHome = e.salary - e.insurance - e.tax;
      const disposable = takeHome + e.other - e.family;
      const fixedCost = fixedCostFor(e.month, expenses);
      const deposits = e.fixed + e.personal;
      const saved = deposits - e.fundWithdrawal;
      let savedPersonal = e.personal - e.fundWithdrawal;
      let savedFixed = e.fixed;
      if (savedPersonal < 0) {
        savedFixed += savedPersonal;
        savedPersonal = 0;
      }
      const fundSelf = Math.min(e.fund / 2, e.insurance);
      balanceFixed += e.fixed;
      balancePersonal += e.personal;
      fund += e.fund - e.fundWithdrawal;
      if (anchor && anchor.asOf === e.month) fund = anchor.amount;
      const balanceFund = Math.max(0, fund);
      return {
        ...e,
        gross: e.salary + e.other,
        baseSalary: e.salary - e.bonus,
        takeHome,
        disposable,
        fixedCost,
        deposits,
        saved,
        savedFixed,
        savedPersonal,
        leftover: disposable - fixedCost - saved,
        rate: disposable > 0 ? saved / disposable : null,
        fundSelf,
        social: e.insurance - fundSelf,
        balance: balanceFixed + balancePersonal + balanceFund,
        balanceFixed,
        balancePersonal,
        balanceFund,
      };
    });
}

export interface Period {
  key: string;
  label: string;
  range: string;
}

export function periodOf(month: string, mode: Mode): Period {
  const y = Number(month.slice(0, 4));
  const m = Number(month.slice(5, 7));
  if (mode === "calendar") return { key: String(y), label: `${y}年`, range: `${y}.1–${y}.12` };
  const start = m >= 7 ? y : y - 1;
  return {
    key: String(start),
    label: `${String(start).slice(2)}/${String(start + 1).slice(2)}年度`,
    range: `${start}.7–${start + 1}.6`,
  };
}

export function periods(rows: Row[], mode: Mode): Period[] {
  const map = new Map<string, Period>();
  for (const row of rows) {
    const p = periodOf(row.month, mode);
    map.set(p.key, p);
  }
  return [...map.values()].sort((a, b) => a.key.localeCompare(b.key));
}

export const inPeriod = (rows: Row[], mode: Mode, key: string) =>
  key === "all" ? rows : rows.filter((r) => periodOf(r.month, mode).key === key);

const SUM_FIELDS = [
  "gross", "salary", "bonus", "baseSalary", "insurance", "tax", "other", "family", "fund", "takeHome", "disposable",
  "fixedCost", "deposits", "saved", "savedFixed", "savedPersonal", "leftover", "fundSelf", "social", "fixed",
  "personal", "fundWithdrawal",
] as const;
export type SumField = (typeof SUM_FIELDS)[number];

export interface Summary extends Record<SumField, number> {
  months: number;
  normalMonths: number;
  rate: number | null;
  taxRate: number | null;
  avgDisposable: number;
  avgSaved: number;
  avgBaseSalary: number;
  avgLeftover: number;
  avgTakeHome: number;
  avgSalary: number;
  otherMonths: number;
}

export function summarize(rows: Row[]): Summary {
  const total = Object.fromEntries(SUM_FIELDS.map((f) => [f, 0])) as Record<SumField, number>;
  for (const row of rows) for (const f of SUM_FIELDS) total[f] += row[f];
  const normal = rows.filter((r) => !r.special);
  const avg = (f: SumField) => (normal.length ? normal.reduce((s, r) => s + r[f], 0) / normal.length : 0);
  return {
    ...total,
    months: rows.length,
    normalMonths: normal.length,
    rate: total.disposable > 0 ? total.saved / total.disposable : null,
    taxRate: total.salary > 0 ? total.tax / total.salary : null,
    avgDisposable: avg("disposable"),
    avgSaved: avg("saved"),
    avgBaseSalary: avg("baseSalary"),
    avgLeftover: avg("leftover"),
    avgTakeHome: avg("takeHome"),
    avgSalary: avg("salary"),
    otherMonths: rows.filter((r) => r.other > 0).length,
  };
}

/** 去年同期：同样的月份往前推 12 个月 */
export function samePeriodLastYear(all: Row[], selected: Row[]) {
  const wanted = new Set(selected.map((r) => addMonths(r.month, -12)));
  const rows = all.filter((r) => wanted.has(r.month));
  return rows.length === selected.length && rows.length > 0 ? rows : null;
}

/** 滚动 12 个月储蓄率 */
export function rollingRate(rows: Row[]) {
  return rows.map((row, i) => {
    const window = rows.slice(Math.max(0, i - 11), i + 1);
    const disposable = window.reduce((s, r) => s + r.disposable, 0);
    const saved = window.reduce((s, r) => s + r.saved, 0);
    return { month: row.month, rate: disposable > 0 ? saved / disposable : 0, full: window.length === 12 };
  });
}

export interface AssetPoint {
  month: string;
  cash: number;
  fund: number;
  other: number;
  total: number;
}

/** 总资产估算：累计存入 + 公积金余额（缴存 − 提取，可用实际余额校准）+ 其他资产 */
export function assetSeries(rows: Row[], settings: Settings): AssetPoint[] {
  let cash = 0;
  let fund = 0;
  const anchor = settings.fundBalance;
  return rows.map((r) => {
    cash += r.deposits;
    fund += r.fund - r.fundWithdrawal;
    if (anchor && r.month === anchor.asOf) fund = anchor.amount;
    const fundShown = Math.max(0, fund);
    return { month: r.month, cash, fund: fundShown, other: settings.otherAssets, total: cash + fundShown + settings.otherAssets };
  });
}

export function projectGoal(series: AssetPoint[], goal: number) {
  if (!series.length) return null;
  const last = series[series.length - 1];
  const back = series[Math.max(0, series.length - 13)];
  const span = monthIndex(last.month) - monthIndex(back.month);
  const monthly = span > 0 ? (last.total - back.total) / span : 0;
  if (last.total >= goal) return { monthly, months: 0, month: last.month };
  if (monthly <= 0) return { monthly, months: null, month: null };
  const months = Math.ceil((goal - last.total) / monthly);
  return { monthly, months, month: addMonths(last.month, months) };
}

export const WATCH_FIELDS = ["salary", "insurance", "tax", "fund", "family"] as const;

export function anomalies(input: Partial<Entry>, previous: Entry | undefined) {
  const notes: Partial<Record<(typeof WATCH_FIELDS)[number], string>> = {};
  if (!previous) return notes;
  for (const f of WATCH_FIELDS) {
    const now = Number(input[f] || 0);
    const before = previous[f];
    if (before > 0 && Math.abs(now - before) / before > 0.3) {
      notes[f] = `比上月（${Math.round(before).toLocaleString("zh-CN")}）${now > before ? "高" : "低"} ${Math.round(
        (Math.abs(now - before) / before) * 100,
      )}%`;
    }
  }
  return notes;
}

export function taxByYear(rows: Row[]) {
  const map = new Map<string, Row[]>();
  for (const r of rows) {
    const y = r.month.slice(0, 4);
    map.set(y, [...(map.get(y) || []), r]);
  }
  return [...map.entries()].map(([year, list]) => ({ year, rows: list, summary: summarize(list) }));
}
