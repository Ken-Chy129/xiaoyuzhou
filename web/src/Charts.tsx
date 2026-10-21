import { useMemo, useState } from "react";
import { addMonths, monthIndex, rollingRate, type AssetPoint, type Row } from "./calc";
import { monthLabel, percent, shortMonth, wan, yuan } from "./format";
import { useWidth } from "./useWidth";

function niceMax(value: number) {
  if (value <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(value)));
  const steps = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  return (steps.find((s) => s * exp >= value) || 10) * exp;
}

interface TipProps {
  x: number;
  y: number;
  width: number;
  children: React.ReactNode;
}
function Tip({ x, y, width, children }: TipProps) {
  const left = Math.min(Math.max(x, 110), width - 110);
  return (
    <div className="chart-tip" style={{ left, top: y }}>
      {children}
    </div>
  );
}

/* ---------- 月度收支 ---------- */

export function MonthlyChart({ rows, onSelect }: { rows: Row[]; onSelect: (row: Row) => void }) {
  const [ref, width] = useWidth<HTMLDivElement>(720);
  const [hover, setHover] = useState<number | null>(null);
  const height = 280;
  const pad = { top: 16, right: 8, bottom: 26, left: 44 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;
  const max = niceMax(Math.max(...rows.map((r) => Math.max(r.gross, r.saved + r.fixedCost, r.disposable)), 1));
  const y = (v: number) => pad.top + innerH - (Math.max(0, v) / max) * innerH;
  const step = innerW / Math.max(rows.length, 1);
  const barW = Math.max(4, Math.min(28, step * 0.62));
  const cx = (i: number) => pad.left + step * i + step / 2;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => t * max);
  const labelEvery = Math.ceil(rows.length / Math.max(1, Math.floor(innerW / 46)));
  const line = rows.map((r, i) => `${i ? "L" : "M"}${cx(i)},${y(r.gross)}`).join("");
  const h = hover === null ? null : rows[hover];

  return (
    <div className="chart" ref={ref} onMouseLeave={() => setHover(null)}>
      <svg width={width} height={height} role="img" aria-label="月度收支图">
        {ticks.map((t) => (
          <g key={t}>
            <line className="grid" x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} />
            <text className="tick tick-money" x={pad.left - 8} y={y(t) + 4} textAnchor="end">
              {wan(t)}
            </text>
          </g>
        ))}
        {rows.map((r, i) => {
          const x = cx(i) - barW / 2;
          const saved = Math.max(0, r.saved);
          const cost = Math.max(0, r.fixedCost);
          const rest = Math.max(0, r.leftover);
          const dim = hover !== null && hover !== i;
          return (
            <g key={r.month} className="bar-group" opacity={dim ? 0.45 : 1}>
              <rect className="bar-saved" x={x} width={barW} y={y(saved)} height={y(0) - y(saved)} rx={2} />
              <rect className="bar-cost" x={x} width={barW} y={y(saved + cost)} height={y(saved) - y(saved + cost)} />
              <rect className="bar-free" x={x} width={barW} y={y(saved + cost + rest)} height={y(saved + cost) - y(saved + cost + rest)} rx={2} />
              {r.leftover < 0 && (
                <line className="over-mark" x1={x - 2} x2={x + barW + 2} y1={y(r.disposable)} y2={y(r.disposable)} />
              )}
              {r.special && <circle className="special-dot" cx={cx(i)} cy={height - pad.bottom + 18} r={2.5} />}
              {i % labelEvery === 0 && (
                <text className="tick" x={cx(i)} y={height - 8} textAnchor="middle">
                  {shortMonth(r.month)}
                </text>
              )}
            </g>
          );
        })}
        <path className="salary-line" d={line} />
        {rows.map((r, i) => (
          <circle key={r.month} className="salary-dot" cx={cx(i)} cy={y(r.gross)} r={hover === i ? 4.5 : rows.length > 30 ? 0 : 2.5} />
        ))}
        {rows.map((r, i) => (
          <rect
            key={r.month}
            className="hit"
            x={pad.left + step * i}
            y={pad.top}
            width={step}
            height={innerH}
            fill="transparent"
            onMouseEnter={() => setHover(i)}
            onClick={() => onSelect(r)}
          />
        ))}
      </svg>
      {h && hover !== null && (
        <Tip x={cx(hover)} y={Math.min(y(Math.max(h.gross, h.disposable)), height - 160)} width={width}>
          <strong>
            {monthLabel(h.month)}
            {h.special ? " · 特殊月" : ""}
          </strong>
          <span>
            <i className="sw salary line" />工资 + 附加<b className="money">{yuan(h.gross)}</b>
          </span>
          {h.bonus > 0 && (
            <span className="sub">
              其中奖金<b className="money">{yuan(h.bonus)}</b>
            </span>
          )}
          {h.other > 0 && (
            <span className="sub">
              附加收入<b className="money">{yuan(h.other)}</b>
            </span>
          )}
          <span>
            到手<b className="money">{yuan(h.takeHome)}</b>
          </span>
          {h.family > 0 && (
            <span className="sub">
              家庭补贴<b className="money">−{yuan(h.family)}</b>
            </span>
          )}
          <span>
            <i className="sw saved" />存下<b className="money">{yuan(h.saved)}</b>
          </span>
          {h.fixedCost > 0 && (
            <span>
              <i className="sw cost" />固定开销<b className="money">{yuan(h.fixedCost)}</b>
            </span>
          )}
          <span>
            <i className="sw net" />其他花销<b className="money">{yuan(h.leftover)}</b>
          </span>
          <em>{h.note || "点击编辑"}</em>
        </Tip>
      )}
    </div>
  );
}

/* ---------- 储蓄率 ---------- */

export function RateChart({ rows }: { rows: Row[] }) {
  const [ref, width] = useWidth<HTMLDivElement>(400);
  const [hover, setHover] = useState<number | null>(null);
  const rolling = useMemo(() => rollingRate(rows), [rows]);
  const height = 170;
  const pad = { top: 12, right: 8, bottom: 22, left: 36 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;
  const clamp = (v: number) => Math.max(0, Math.min(1, v));
  const y = (v: number) => pad.top + innerH - clamp(v) * innerH;
  const step = innerW / Math.max(rows.length, 1);
  const cx = (i: number) => pad.left + step * i + step / 2;
  const line = rolling.map((p, i) => `${i ? "L" : "M"}${cx(i)},${y(p.rate)}`).join("");
  const labelEvery = Math.ceil(rows.length / Math.max(1, Math.floor(innerW / 46)));
  const r = hover === null ? null : rows[hover];

  return (
    <div className="chart" ref={ref} onMouseLeave={() => setHover(null)}>
      <svg width={width} height={height} role="img" aria-label="储蓄率走势">
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line className="grid" x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} />
            <text className="tick" x={pad.left - 6} y={y(t) + 4} textAnchor="end">
              {t * 100}%
            </text>
          </g>
        ))}
        {rows.map((row, i) => {
          const v = row.rate ?? 0;
          const over = v > 1;
          return (
            <g key={row.month} opacity={hover !== null && hover !== i ? 0.4 : 1}>
              <rect
                className={over ? "rate-bar over" : "rate-bar"}
                x={cx(i) - Math.min(10, step * 0.5) / 2}
                width={Math.min(10, step * 0.5)}
                y={y(v)}
                height={y(0) - y(v)}
                rx={2}
              />
              {i % labelEvery === 0 && (
                <text className="tick" x={cx(i)} y={height - 6} textAnchor="middle">
                  {shortMonth(row.month)}
                </text>
              )}
            </g>
          );
        })}
        <path className="rate-line" d={line} />
        {rows.map((row, i) => (
          <rect
            key={row.month}
            x={pad.left + step * i}
            y={pad.top}
            width={step}
            height={innerH}
            fill="transparent"
            onMouseEnter={() => setHover(i)}
          />
        ))}
      </svg>
      {r && hover !== null && (
        <Tip x={cx(hover)} y={pad.top + 10} width={width}>
          <strong>{monthLabel(r.month)}</strong>
          <span>
            当月<b>{r.rate === null ? "—" : percent(r.rate, 0)}</b>
          </span>
          <span>
            滚动 12 个月<b>{percent(rolling[hover].rate, 1)}</b>
          </span>
          {!rolling[hover].full && <em>不足 12 个月，按已有月份计算</em>}
          {(r.rate ?? 0) > 1 && <em>存下超过可支配：动用了往期结余或公积金提取</em>}
        </Tip>
      )}
    </div>
  );
}

/* ---------- 资产与目标 ---------- */

interface Projection {
  monthly: number;
  months: number | null;
  month: string | null;
}

export function AssetChart({ series, goal, projection }: { series: AssetPoint[]; goal: number; projection: Projection | null }) {
  const [ref, width] = useWidth<HTMLDivElement>(640);
  const [hover, setHover] = useState<number | null>(null);
  const height = 240;
  const pad = { top: 18, right: 12, bottom: 24, left: 44 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;
  if (!series.length) return <div ref={ref} className="empty muted">暂无数据</div>;

  const first = monthIndex(series[0].month);
  const last = series[series.length - 1];
  const endMonth = projection?.month && projection.months ? projection.month : last.month;
  const end = Math.max(monthIndex(endMonth), monthIndex(last.month) + 3);
  const x = (m: string) => pad.left + ((monthIndex(m) - first) / Math.max(1, end - first)) * innerW;
  const max = niceMax(Math.max(goal * 1.05, last.total));
  const y = (v: number) => pad.top + innerH - (v / max) * innerH;

  const area = (pick: (p: AssetPoint) => number, base: (p: AssetPoint) => number) =>
    series.map((p, i) => `${i ? "L" : "M"}${x(p.month)},${y(pick(p))}`).join("") +
    [...series].reverse().map((p) => `L${x(p.month)},${y(base(p))}`).join("") +
    "Z";
  const projected =
    projection && projection.monthly > 0 && projection.month && projection.months
      ? `M${x(last.month)},${y(last.total)}L${x(projection.month)},${y(goal)}`
      : "";
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => t * max);
  const years: string[] = [];
  for (let m = first; m <= end; m++) if (m % 12 === 0) years.push(`${m / 12}-01`);
  const h = hover === null ? null : series[hover];

  return (
    <div className="chart" ref={ref} onMouseLeave={() => setHover(null)}>
      <svg width={width} height={height} role="img" aria-label="资产累计与目标">
        {ticks.map((t) => (
          <g key={t}>
            <line className="grid" x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} />
            <text className="tick tick-money" x={pad.left - 8} y={y(t) + 4} textAnchor="end">
              {wan(t)}
            </text>
          </g>
        ))}
        {years.map((m) => (
          <text key={m} className="tick" x={x(m)} y={height - 6} textAnchor="middle">
            {m.slice(0, 4)}
          </text>
        ))}
        <line className="goal-line" x1={pad.left} x2={width - pad.right} y1={y(goal)} y2={y(goal)} />
        <text className="goal-label tick-money" x={width - pad.right} y={y(goal) - 6} textAnchor="end">
          目标 {wan(goal)}
        </text>
        <path className="area-fund" d={area((p) => p.total, (p) => p.cash + p.other)} />
        <path className="area-cash" d={area((p) => p.cash + p.other, () => 0)} />
        <path className="goal-actual" d={series.map((p, i) => `${i ? "L" : "M"}${x(p.month)},${y(p.total)}`).join("")} />
        {projected && <path className="goal-projected" d={projected} />}
        {projected && projection?.month && <circle className="dot-projected" cx={x(projection.month)} cy={y(goal)} r={4} />}
        {h && (
          <>
            <line className="cursor" x1={x(h.month)} x2={x(h.month)} y1={pad.top} y2={y(0)} />
            <circle className="dot-actual" cx={x(h.month)} cy={y(h.total)} r={4.5} />
          </>
        )}
        {series.map((p, i) => {
          const left = i ? (x(series[i - 1].month) + x(p.month)) / 2 : pad.left;
          const right = i < series.length - 1 ? (x(p.month) + x(series[i + 1].month)) / 2 : x(p.month) + 12;
          return (
            <rect key={p.month} x={left} y={pad.top} width={right - left} height={innerH} fill="transparent" onMouseEnter={() => setHover(i)} />
          );
        })}
      </svg>
      {h && hover !== null && (
        <Tip x={x(h.month)} y={Math.max(y(h.total) - 8, 90)} width={width}>
          <strong>{monthLabel(h.month)}</strong>
          <span>
            <i className="sw saved" />累计存款<b className="money">{yuan(h.cash)}</b>
          </span>
          <span>
            <i className="sw fund" />公积金余额<b className="money">{yuan(h.fund)}</b>
          </span>
          {h.other > 0 && (
            <span>
              其他资产<b className="money">{yuan(h.other)}</b>
            </span>
          )}
          <em>
            合计 <span className="money">{yuan(h.total)}</span> · 目标的 {percent(h.total / goal, 0)}
          </em>
        </Tip>
      )}
    </div>
  );
}

/* ---------- 迷你柱状（明细里用） ---------- */

/* ---------- 存款走势（首卡） ---------- */

export function BalanceSpark({ rows, from }: { rows: Row[]; from: string | null }) {
  const [ref, width] = useWidth<HTMLDivElement>(360);
  const [hover, setHover] = useState<number | null>(null);
  const height = 96;
  const pad = { top: 8, bottom: 18, x: 2 };
  if (rows.length < 2) return <div ref={ref} />;
  const max = niceMax(rows.at(-1)!.balance);
  const x = (i: number) => pad.x + (i / (rows.length - 1)) * (width - pad.x * 2);
  const y = (v: number) => pad.top + (height - pad.top - pad.bottom) * (1 - v / max);
  const line = rows.map((r, i) => `${i ? "L" : "M"}${x(i)},${y(r.balance)}`).join("");
  const startIdx = from ? Math.max(0, rows.findIndex((r) => r.month >= from)) : 0;
  const h = hover === null ? null : rows[hover];
  return (
    <div className="chart balance-spark" ref={ref} onMouseLeave={() => setHover(null)}>
      <svg width={width} height={height} aria-hidden>
        <defs>
          <linearGradient id="bal-fill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="currentColor" stopOpacity="0.28" />
            <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
          </linearGradient>
        </defs>
        {startIdx > 0 && (
          <rect x={x(startIdx)} y={0} width={width - x(startIdx)} height={height - pad.bottom} className="spark-range" />
        )}
        <path d={`${line}L${x(rows.length - 1)},${y(0)}L${x(0)},${y(0)}Z`} fill="url(#bal-fill)" />
        <path d={line} className="spark-stroke" />
        <circle cx={x(rows.length - 1)} cy={y(rows.at(-1)!.balance)} r={3.5} className="spark-end" />
        {h && hover !== null && <circle cx={x(hover)} cy={y(h.balance)} r={3.5} className="spark-end" />}
        <text x={x(0)} y={height - 4} className="spark-tick">{shortMonth(rows[0].month)}</text>
        <text x={x(rows.length - 1)} y={height - 4} textAnchor="end" className="spark-tick">{shortMonth(rows.at(-1)!.month)}</text>
        {rows.map((r, i) => (
          <rect key={r.month} x={x(i) - width / rows.length / 2} y={0} width={width / rows.length} height={height} fill="transparent" onMouseEnter={() => setHover(i)} />
        ))}
      </svg>
      {h && hover !== null && (
        <Tip x={x(hover)} y={y(h.balance) - 6} width={width}>
          <strong>{monthLabel(h.month)}</strong>
          <span>当前存款<b className="money">{yuan(h.balance)}</b></span>
          <span>当月存入<b className="money">{yuan(h.deposits)}</b></span>
        </Tip>
      )}
    </div>
  );
}

export function Spark({ values, highlight }: { values: number[]; highlight?: number }) {
  const max = Math.max(...values, 1);
  return (
    <svg className="spark" width={values.length * 6} height={22} aria-hidden>
      {values.map((v, i) => (
        <rect key={i} x={i * 6} width={4} y={22 - (Math.max(0, v) / max) * 22} height={(Math.max(0, v) / max) * 22} rx={1} className={i === highlight ? "on" : ""} />
      ))}
    </svg>
  );
}

export const nextMonths = (from: string, n: number) => Array.from({ length: n }, (_, i) => addMonths(from, i + 1));
