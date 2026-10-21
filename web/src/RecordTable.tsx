import { useMemo, useState } from "react";
import type { Row, Summary } from "./calc";
import { DEFINITIONS, Info } from "./Info";
import { monthLabel, percent, plain } from "./format";

type Col = {
  key: keyof Row;
  label: string;
  tip?: string;
  hide?: "sm" | "md";
  strong?: boolean;
  render?: (r: Row) => React.ReactNode;
  total?: (s: Summary) => React.ReactNode;
};

const money = (v: number) => <span className="money">{v ? plain(v) : "—"}</span>;

const COLUMNS: Col[] = [
  { key: "salary", label: "工资收入", tip: DEFINITIONS.salary, render: (r) => (
    <span className="money">
      {plain(r.salary)}
      {r.bonus > 0 && <i className="badge">奖</i>}
    </span>
  ) },
  { key: "other", label: "附加收入", tip: DEFINITIONS.other },
  { key: "insurance", label: "五险一金", hide: "md" },
  { key: "tax", label: "个税", hide: "md" },
  { key: "takeHome", label: "到手", tip: DEFINITIONS.takeHome, strong: true },
  { key: "family", label: "家庭补贴", hide: "md" },
  { key: "fixed", label: "定存", hide: "sm" },
  { key: "personal", label: "个人存储", hide: "sm" },
  { key: "saved", label: "存下", tip: DEFINITIONS.saved, strong: true, render: (r) => (
    <span className="money">
      {plain(r.saved)}
      {r.fundWithdrawal > 0 && <i className="badge" title={`另有公积金提取 ${plain(r.fundWithdrawal)} 转入`}>提</i>}
    </span>
  ) },
  {
    key: "rate",
    label: "储蓄率",
    tip: DEFINITIONS.rate,
    render: (r) => (r.rate === null ? "—" : <span className={r.rate > 1 ? "over-text" : ""}>{percent(r.rate, 0)}</span>),
    total: (s) => (s.rate === null ? "—" : percent(s.rate, 1)),
  },
  {
    key: "balance",
    label: "当前存款",
    tip: DEFINITIONS.current,
    hide: "sm",
    total: () => "",
  },
  { key: "fixedCost", label: "固定开销", hide: "md" },
  { key: "leftover", label: "其他花销", tip: DEFINITIONS.leftover, hide: "sm", render: (r) => (
    <span className={`money ${r.leftover < 0 ? "down" : ""}`}>{plain(r.leftover)}</span>
  ) },
];

export function RecordTable({ rows, summary, onSelect }: { rows: Row[]; summary: Summary; onSelect: (r: Row) => void }) {
  const [sort, setSort] = useState<{ col: number; desc: boolean }>({ col: -1, desc: true });
  const sorted = useMemo(() => {
    if (sort.col < 0) return [...rows].reverse();
    const col = COLUMNS[sort.col];
    const value = (r: Row) => Number(r[col.key] ?? -Infinity);
    return [...rows].sort((a, b) => (sort.desc ? value(b) - value(a) : value(a) - value(b)));
  }, [rows, sort]);

  const toggle = (i: number) => setSort((s) => (s.col === i ? (s.desc ? { col: i, desc: false } : { col: -1, desc: true }) : { col: i, desc: true }));
  const cls = (c: Col) => ["num", c.hide ? `hide-${c.hide}` : "", c.strong ? "strong" : ""].join(" ");

  return (
    <div className="table-wrap">
      <table className="table records">
        <thead>
          <tr>
            <th>
              <button className="sort" onClick={() => setSort({ col: -1, desc: true })}>
                月份 {sort.col < 0 ? "↓" : ""}
              </button>
            </th>
            {COLUMNS.map((c, i) => (
              <th key={c.label} className={cls(c)} aria-sort={sort.col === i ? (sort.desc ? "descending" : "ascending") : undefined}>
                <span className="th-inner">
                  <button className="sort" onClick={() => toggle(i)}>
                    {c.label}
                    {sort.col === i ? (sort.desc ? " ↓" : " ↑") : ""}
                  </button>
                  {c.tip && <Info tip={c.tip} />}
                </span>
              </th>
            ))}
            <th className="hide-md">备注</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr key={r.id} tabIndex={0} onClick={() => onSelect(r)} onKeyDown={(e) => e.key === "Enter" && onSelect(r)}>
              <td className="month">
                {monthLabel(r.month)}
                {r.special && <i className="badge muted-badge">特殊</i>}
              </td>
              {COLUMNS.map((c) => (
                <td key={c.label} className={cls(c)}>
                  {c.render ? c.render(r) : money(Number(r[c.key]))}
                </td>
              ))}
              <td className="note hide-md" title={r.note}>
                {r.note}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td>合计 · {summary.months} 个月</td>
            {COLUMNS.map((c) => (
              <td key={c.label} className={cls(c)}>
                {c.total ? c.total(summary) : money(Number(summary[c.key as keyof Summary] ?? 0))}
              </td>
            ))}
            <td className="hide-md" />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
