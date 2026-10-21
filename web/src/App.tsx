import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError } from "./api";
import {
  addMonths, assetSeries, derive, inPeriod, periods, projectGoal, samePeriodLastYear, summarize, taxByYear,
  type Mode, type Row, type Summary,
} from "./calc";
import { AssetChart, BalanceSpark, MonthlyChart, RateChart } from "./Charts";
import { delta, monthLabel, percent, plain, wan, yuan } from "./format";
import { DEFINITIONS, Info } from "./Info";
import { RecordEditor } from "./RecordEditor";
import { RecordTable } from "./RecordTable";
import { SettingsDrawer } from "./SettingsDrawer";
import type { Entry, EntryInput, Expense, LedgerData, Settings } from "./types";
import { Todos } from "./Todos";

type Auth = "checking" | "out" | "in";

export default function App() {
  const [auth, setAuth] = useState<Auth>("checking");
  useEffect(() => {
    api.session().then((s) => setAuth(s.authenticated ? "in" : "out"), () => setAuth("out"));
  }, []);
  if (auth === "checking") return <div className="splash" />;
  if (auth === "out") return <Login onDone={() => setAuth("in")} />;
  return <Shell onLogout={() => setAuth("out")} />;
}

export type View = "ledger" | "todo";
const viewOf = (): View => (location.hash.startsWith("#todo") ? "todo" : "ledger");

/** 两个页面：资产账本 / 待办。用地址里的 #todo 区分，方便电脑上直接收藏待办页 */
function Shell({ onLogout }: { onLogout: () => void }) {
  const [view, setView] = useState<View>(viewOf);
  useEffect(() => {
    const on = () => setView(viewOf());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  useEffect(() => {
    document.title = view === "todo" ? "待办 · 小宇宙" : "账本 · 小宇宙";
  }, [view]);
  const go = (v: View) => {
    location.hash = v === "todo" ? "#todo" : "";
    setView(v);
  };
  return view === "todo" ? <Todos onLogout={onLogout} nav={<ViewNav view={view} go={go} />} /> : <Dashboard onLogout={onLogout} nav={<ViewNav view={view} go={go} />} />;
}

export function ViewNav({ view, go }: { view: View; go: (v: View) => void }) {
  return (
    <nav className="segmented view-nav" role="tablist" aria-label="页面">
      <button role="tab" aria-selected={view === "ledger"} onClick={() => go("ledger")}>账本</button>
      <button role="tab" aria-selected={view === "todo"} onClick={() => go("todo")}>待办</button>
    </nav>
  );
}

function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api.login(username, password);
      onDone();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }
  return (
    <main className="login">
      <form className="login-card" onSubmit={submit}>
        <img className="mark-img big" src="/logo.png" alt="" />
        <h1>小宇宙</h1>
        <p>登录后查看账本和待办</p>
        <label className="field">
          <span>账号</span>
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </label>
        <label className="field">
          <span>密码</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p className="form-error">{error}</p>}
        <button className="btn primary block" disabled={busy}>
          {busy ? "登录中…" : "登录"}
        </button>
      </form>
    </main>
  );
}

function Dashboard({ onLogout, nav }: { onLogout: () => void; nav: React.ReactNode }) {
  const [data, setData] = useState<LedgerData | null>(null);
  const [error, setError] = useState("");
  const [mode, setMode] = useState<Mode>(() => (localStorage.getItem("ledger-mode") as Mode) || "fiscal");
  const [period, setPeriod] = useState<string | null>(null);
  const [privacy, setPrivacy] = useState(() => localStorage.getItem("ledger-privacy") === "1");
  const [editing, setEditing] = useState<Entry | "new" | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [toast, setToast] = useState("");

  const handle = useCallback(
    (err: unknown) => {
      if (err instanceof ApiError && err.status === 401) onLogout();
      else throw err;
    },
    [onLogout],
  );

  const load = useCallback(() => {
    setError("");
    api.data().then(setData, (err) => {
      if (err instanceof ApiError && err.status === 401) onLogout();
      else setError((err as Error).message);
    });
  }, [onLogout]);
  useEffect(load, [load]);

  useEffect(() => {
    document.body.classList.toggle("privacy", privacy);
    localStorage.setItem("ledger-privacy", privacy ? "1" : "0");
  }, [privacy]);
  useEffect(() => localStorage.setItem("ledger-mode", mode), [mode]);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(""), 2400);
    return () => clearTimeout(t);
  }, [toast]);

  const rows = useMemo(() => (data ? derive(data.records, data.expenses, data.settings) : []), [data]);
  const allPeriods = useMemo(() => periods(rows, mode), [rows, mode]);
  const activeKey = period && allPeriods.some((p) => p.key === period) ? period : "all";
  const selected = useMemo(() => inPeriod(rows, mode, activeKey), [rows, mode, activeKey]);

  const mutate = async (fn: () => Promise<LedgerData>, message: string) => {
    try {
      setData(await fn());
      setToast(message);
    } catch (err) {
      handle(err);
    }
  };

  const saveRecord = async (input: EntryInput) => {
    const target = editing;
    await mutate(
      () => (target && target !== "new" ? api.updateRecord(target.id, input) : api.createRecord(input)),
      `${monthLabel(input.month)} 已保存`,
    );
    setEditing(null);
  };
  const deleteRecord = async (entry: Entry) => {
    await mutate(() => api.deleteRecord(entry.id), `${monthLabel(entry.month)} 已删除`);
    setEditing(null);
  };
  const saveExpense = (id: string | null, input: Omit<Expense, "id">) =>
    mutate(() => (id ? api.updateExpense(id, input) : api.createExpense(input)), "固定开销已保存");
  const deleteExpense = (id: string) => mutate(() => api.deleteExpense(id), "固定开销已删除");
  const saveSettings = (input: Partial<Settings>) => mutate(() => api.saveSettings(input), "设置已保存");

  const latest = rows.at(-1)?.month || new Date().toISOString().slice(0, 7);

  return (
    <>
      <header className="top">
        <div className="brand">
          <div className="mark small">账</div>
          <div>
            <strong>收入账本</strong>
            <span>{rows.length ? `${monthLabel(rows[0].month)} — ${monthLabel(latest)} · ${rows.length} 个月` : "加载中"}</span>
          </div>
        </div>
        {nav}
        <div className="top-actions">
          <button className="btn ghost" aria-pressed={privacy} onClick={() => setPrivacy((p) => !p)} title="隐藏金额">
            {privacy ? "显示金额" : "隐藏金额"}
          </button>
          <button className="btn ghost hide-sm" onClick={() => setSettingsOpen(true)}>
            设置
          </button>
          <button className="btn ghost show-sm icon" onClick={() => setSettingsOpen(true)} aria-label="设置">
            ⚙
          </button>
          <button className="btn primary" onClick={() => setEditing("new")} disabled={!data}>
            记一笔
          </button>
          <button className="btn ghost hide-sm" onClick={() => api.logout().finally(onLogout)}>
            退出
          </button>
        </div>
      </header>

      <main className="page">
        {error && (
          <div className="banner">
            <span>{error}</span>
            <button className="btn" onClick={load}>
              重试
            </button>
          </div>
        )}
        {!data ? (
          !error && <div className="skeleton" />
        ) : (
          <>
            <div className="period-bar">
              <div className="segmented" role="tablist" aria-label="统计口径">
                <button role="tab" aria-selected={mode === "fiscal"} onClick={() => setMode("fiscal")}>
                  7 月年度
                </button>
                <button role="tab" aria-selected={mode === "calendar"} onClick={() => setMode("calendar")}>
                  自然年
                </button>
              </div>
              <div className="years" role="tablist">
                {allPeriods.map((p) => (
                  <button key={p.key} className={activeKey === p.key ? "active" : ""} onClick={() => setPeriod(p.key)} title={p.range}>
                    {p.label}
                  </button>
                ))}
                <button className={activeKey === "all" ? "active" : ""} onClick={() => setPeriod("all")}>
                  全部
                </button>
              </div>
            </div>

            <Overview all={rows} selected={selected} isAll={activeKey === "all"} settings={data.settings} />

            <div className="grid-main">
              <section className="panel">
                <div className="panel-head">
                  <div>
                    <h2>月度收支</h2>
                    <p>折线是工资 + 附加收入，柱子是到手后的钱怎么分配。点柱子可编辑</p>
                  </div>
                  <div className="legend">
                    <span><i className="sw saved" />存下</span>
                    {selected.some((r) => r.fixedCost > 0) && <span><i className="sw cost" />固定开销</span>}
                    <span><i className="sw net" />其他花销</span>
                    <span><i className="sw salary line" />工资 + 附加</span>
                  </div>
                </div>
                <MonthlyChart rows={selected} onSelect={(r) => setEditing(data.records.find((e) => e.id === r.id) || null)} />
              </section>
              <Flow rows={selected} onOpenSettings={() => setSettingsOpen(true)} />
            </div>

            <div className="grid-two">
              <Goal rows={rows} settings={data.settings} onOpenSettings={() => setSettingsOpen(true)} />
              <section className="panel">
                <div className="panel-head">
                  <div>
                    <h2>
                      储蓄率 <Info tip={DEFINITIONS.rate} />
                    </h2>
                    <p>柱子是单月，折线是滚动 12 个月</p>
                  </div>
                </div>
                <RateChart rows={rows} />
                <TaxStrip rows={rows} />
              </section>
            </div>

            <PeriodTable rows={rows} mode={mode} activeKey={activeKey} onPick={setPeriod} />

            <section className="panel">
              <div className="panel-head">
                <div>
                  <h2>明细</h2>
                  <p>点表头排序，点行编辑。「奖」= 含奖金，「提」= 存款里有公积金提取</p>
                </div>
              </div>
              <RecordTable
                rows={selected}
                summary={summarize(selected)}
                onSelect={(r) => setEditing(data.records.find((e) => e.id === r.id) || null)}
              />
            </section>

            <Timeline rows={rows} onSelect={(r) => setEditing(data.records.find((e) => e.id === r.id) || null)} />
          </>
        )}
      </main>

      {editing && data && (
        <RecordEditor
          entry={editing === "new" ? null : editing}
          records={data.records}
          expenses={data.expenses}
          suggestedMonth={addMonths(latest, 1)}
          onClose={() => setEditing(null)}
          onSave={saveRecord}
          onDelete={deleteRecord}
        />
      )}
      {settingsOpen && data && (
        <SettingsDrawer
          expenses={data.expenses}
          settings={data.settings}
          latestMonth={latest}
          onClose={() => setSettingsOpen(false)}
          onSaveExpense={saveExpense}
          onDeleteExpense={deleteExpense}
          onSaveSettings={saveSettings}
        />
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}

/* ---------- KPI ---------- */

function Change({ now, before, invert = false, pp = false }: { now: number | null; before: number | null; invert?: boolean; pp?: boolean }) {
  if (now === null || before === null) return null;
  const d = pp ? now - before : delta(now, before);
  if (d === null || !Number.isFinite(d)) return null;
  const good = invert ? d < 0 : d > 0;
  const text = pp ? `${d > 0 ? "+" : ""}${(d * 100).toFixed(1)}pp` : `${d > 0 ? "+" : ""}${(d * 100).toFixed(0)}%`;
  return <span className={good ? "up" : "down"}>{text}</span>;
}

function Overview({ all, selected, isAll, settings }: { all: Row[]; selected: Row[]; isAll: boolean; settings: Settings }) {
  const s = summarize(selected);
  const lastYearRows = samePeriodLastYear(all, selected);
  const ly = lastYearRows ? summarize(lastYearRows) : null;
  const end = selected.at(-1) || all.at(-1);
  const balance = end?.balance || 0;
  const lastMonth = all.at(-1);
  const recent = all.slice(-12);
  const recentAvg = recent.length ? recent.reduce((t, r) => t + r.deposits, 0) / recent.length : 0;
  const compareLabel = ly ? `较去年同期 ${lastYearRows!.length} 个月` : "";

  return (
    <>
      <div className="overview">
        <section className="hero">
          <div className="hero-main">
            <span className="kpi-label">
              当前存款 <Info tip={DEFINITIONS.current} />
              {!isAll && end && <span className="hero-asof">截至 {monthLabel(end.month)}</span>}
            </span>
            <span className="hero-value money">{yuan(balance)}</span>
            <div className="hero-split">
              <span>
                <i className="sw hero-fixed" />定存 <b className="money">{wan(end?.balanceFixed || 0)}</b>
              </span>
              <span>
                <i className="sw hero-personal" />个人存储 <b className="money">{wan(end?.balancePersonal || 0)}</b>
              </span>
              <span>
                <i className="sw fund" />公积金 <b className="money">{wan(end?.balanceFund || 0)}</b>
              </span>
            </div>
            <dl className="hero-facts">
              {isAll ? (
                <div>
                  <dt>{lastMonth ? `${Number(lastMonth.month.slice(5, 7))} 月存入` : "最近存入"}</dt>
                  <dd className="money">{yuan(lastMonth?.deposits || 0)}</dd>
                </div>
              ) : (
                <div>
                  <dt>本期存入</dt>
                  <dd className="money">{yuan(s.deposits)}</dd>
                </div>
              )}
              <div>
                <dt>近 12 月月均存入</dt>
                <dd className="money">{yuan(recentAvg)}</dd>
              </div>
              <div>
                <dt>目标进度</dt>
                <dd>
                  <span>{percent(balance / settings.goal, 0)}</span>
                  <span className="hero-goal"> · 目标 {wan(settings.goal)}</span>
                </dd>
              </div>
            </dl>
          </div>
          <BalanceSpark rows={all} from={isAll ? null : selected[0]?.month || null} />
        </section>

        <div className="kpis">
          <div className="kpi">
            <span className="kpi-label">
              工资收入 <Info tip={DEFINITIONS.salary} />
            </span>
            <span className="kpi-value money">{yuan(s.salary)}</span>
            <span className="kpi-sub">
              <span>
                月均 <span className="money">{wan(s.avgSalary)}</span>
                {s.bonus > 0 && (
                  <>
                    {" "}· 奖金 <span className="money">{wan(s.bonus)}</span>
                  </>
                )}
              </span>
              <Change now={s.salary} before={ly?.salary ?? null} />
            </span>
          </div>
          <div className="kpi">
            <span className="kpi-label">
              附加收入 <Info tip={DEFINITIONS.other} />
            </span>
            <span className="kpi-value money">{yuan(s.other)}</span>
            <span className="kpi-sub">
              <span>{s.otherMonths ? `${s.otherMonths} 个月有进账` : "暂无"}</span>
              <Change now={s.other} before={ly?.other || null} />
            </span>
          </div>
          <div className="kpi">
            <span className="kpi-label">
              到手 <Info tip={DEFINITIONS.takeHome} />
            </span>
            <span className="kpi-value money">{yuan(s.takeHome)}</span>
            <span className="kpi-sub">
              <span>
                月均 <span className="money">{wan(s.avgTakeHome)}</span> · 到手率 {s.salary ? percent(s.takeHome / s.salary, 0) : "—"}
              </span>
              <Change now={s.takeHome} before={ly?.takeHome ?? null} />
            </span>
          </div>
          <div className="kpi">
            <span className="kpi-label">
              储蓄率 <Info tip={DEFINITIONS.rate} />
            </span>
            <span className="kpi-value">{s.rate === null ? "—" : percent(s.rate)}</span>
            <span className="kpi-sub">
              <span>
                存下 <span className="money">{wan(s.saved)}</span>
              </span>
              <Change now={s.rate} before={ly?.rate ?? null} pp />
            </span>
          </div>
        </div>
      </div>
      <p className="compare-note">
        {selected.length ? `${monthLabel(selected[0].month)} — ${monthLabel(selected.at(-1)!.month)}，共 ${s.months} 个月` : ""}
        {s.normalMonths < s.months ? `；月均不含 ${s.months - s.normalMonths} 个特殊月` : ""}
        {compareLabel ? `；涨跌为${compareLabel}` : ""}
      </p>
    </>
  );
}

/* ---------- 钱去哪了 ---------- */

const FLOW_PARTS: { key: keyof Summary; label: string; cls: string; tip?: string }[] = [
  { key: "social", label: "社保", cls: "c-ins", tip: "五险一金里扣除公积金个人部分后的社保（养老、医疗、失业）。公积金个人部分按双边缴存 ÷ 2 估算" },
  { key: "fundSelf", label: "公积金（个人）", cls: "c-fund", tip: "这部分进了你的公积金账户，仍然是你的资产" },
  { key: "tax", label: "个税", cls: "c-tax" },
  { key: "family", label: "家庭补贴", cls: "c-family" },
  { key: "fixedCost", label: "固定开销", cls: "c-cost" },
  { key: "savedFixed", label: "定存", cls: "c-fixed" },
  { key: "savedPersonal", label: "个人存储", cls: "c-personal" },
  { key: "leftover", label: "其他花销", cls: "c-spend", tip: DEFINITIONS.leftover },
];

function Flow({ rows, onOpenSettings }: { rows: Row[]; onOpenSettings: () => void }) {
  const s = summarize(rows);
  const parts = FLOW_PARTS.map((p) => ({ ...p, value: Math.max(0, s[p.key] as number) })).filter((p) => p.value > 0 || p.key === "leftover");
  const total = parts.reduce((sum, p) => sum + p.value, 0) || 1;
  const drawn = s.leftover < 0 ? -s.leftover : 0;
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>钱去哪了</h2>
          <p>
            工资 + 附加收入的去向 <Info tip={DEFINITIONS.gross} />
          </p>
        </div>
      </div>
      <div className="flow">
        <div className="flow-total">
          <span>工资 + 附加收入</span>
          <b className="money">{yuan(s.gross)}</b>
        </div>
        <div className="stack" aria-hidden>
          {parts.map((p) => (
            <i key={p.key} className={p.cls} style={{ width: `${(p.value / total) * 100}%` }} />
          ))}
        </div>
        <ul className="flow-list">
          {parts.map((p) => (
            <li key={p.key}>
              <span>
                <i className={`dot ${p.cls}`} />
                {p.label}
                {p.tip && <Info tip={p.tip} />}
              </span>
              <span className="money num">{plain(p.value)}</span>
              <span className="muted">{percent(p.value / total, 0)}</span>
            </li>
          ))}
        </ul>
        {drawn > 0 && (
          <p className="hint warn">
            这段时间存下的比可支配多 <span className="money">{yuan(drawn)}</span>，差额来自往期结余或公积金提取。
          </p>
        )}
        {s.fixedCost === 0 && (
          <button className="link-btn" onClick={onOpenSettings}>
            + 添加房租等固定开销，把「其他花销」拆得更细
          </button>
        )}
      </div>
    </section>
  );
}

/* ---------- 目标 ---------- */

function Goal({ rows, settings, onOpenSettings }: { rows: Row[]; settings: Settings; onOpenSettings: () => void }) {
  const series = assetSeries(rows, settings);
  const last = series.at(-1);
  const projection = projectGoal(series, settings.goal);
  const total = last?.total || 0;
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>
            {wan(settings.goal)}目标 <Info tip={DEFINITIONS.assets} />
          </h2>
          <p>{settings.fundBalance ? `公积金已按 ${monthLabel(settings.fundBalance.asOf)} 实际余额校准` : "公积金余额按缴存 − 提取估算"}</p>
        </div>
        <button className="btn ghost" onClick={onOpenSettings}>
          调整
        </button>
      </div>
      <div className="goal-head">
        <span className="money big">{yuan(total)}</span>
        <div className="goal-meta">
          <span>
            完成 <b>{percent(total / settings.goal, 1)}</b>
          </span>
          <span>
            近 12 个月月均增长 <b className="money">{yuan(projection?.monthly || 0)}</b>
          </span>
          <span>
            预计达成{" "}
            <b>{projection?.months === 0 ? "已达成" : projection?.month ? `${monthLabel(projection.month)}（${projection.months} 个月）` : "—"}</b>
          </span>
        </div>
      </div>
      <div className="meter">
        <i style={{ width: `${Math.min(100, (total / settings.goal) * 100)}%` }} />
      </div>
      <AssetChart series={series} goal={settings.goal} projection={projection} />
      <div className="legend legend-foot">
        <span><i className="sw saved" />累计存款</span>
        <span><i className="sw fund" />公积金</span>
        <span><i className="sw dash" />按近 12 个月速度推算</span>
      </div>
    </section>
  );
}

/* ---------- 个税 ---------- */

function TaxStrip({ rows }: { rows: Row[] }) {
  const years = taxByYear(rows).slice(-3);
  return (
    <div className="tax-strip">
      <h3>
        个税实际税率（自然年） <Info tip={DEFINITIONS.tax} />
      </h3>
      <ul>
        {years.map(({ year, rows: list, summary }) => (
          <li key={year}>
            <span>{year}</span>
            <b>{summary.taxRate === null ? "—" : percent(summary.taxRate, 1)}</b>
            <span className="muted money">{wan(summary.tax)}</span>
            <span className="muted">{list.length < 12 ? `${list.length} 个月` : "全年"}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ---------- 年度对比 ---------- */

function PeriodTable({ rows, mode, activeKey, onPick }: { rows: Row[]; mode: Mode; activeKey: string; onPick: (key: string) => void }) {
  const list = periods(rows, mode).map((p) => {
    const items = inPeriod(rows, mode, p.key);
    return { p, s: summarize(items) };
  });
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>{mode === "fiscal" ? "年度对比（7 月—次年 6 月）" : "自然年对比"}</h2>
          <p>不满 12 个月的年份请看月均，别直接比合计。点行切换到该年</p>
        </div>
      </div>
      <div className="table-wrap">
        <table className="table compact">
          <thead>
            <tr>
              <th>年份</th>
              <th className="num">月数</th>
              <th className="num">税前收入</th>
              <th className="num hide-sm">其中奖金</th>
              <th className="num">月均基本工资</th>
              <th className="num hide-sm">附加收入</th>
              <th className="num hide-sm">实际税率</th>
              <th className="num">月均到手</th>
              <th className="num">存下</th>
              <th className="num">储蓄率</th>
              <th className="num hide-md">月均其他花销</th>
            </tr>
          </thead>
          <tbody>
            {list.map(({ p, s }, i) => {
              const prev = list[i - 1]?.s;
              return (
                <tr key={p.key} className={`clickable ${activeKey === p.key ? "current" : ""}`} onClick={() => onPick(p.key)}>
                  <td className="month">
                    {p.label}
                    {s.months < 12 && <i className="badge muted-badge">进行中</i>}
                  </td>
                  <td className="num">{s.months}</td>
                  <td className="num money">{plain(s.gross)}</td>
                  <td className="num money hide-sm">{s.bonus ? plain(s.bonus) : "—"}</td>
                  <td className="num money">
                    {plain(s.avgBaseSalary)} {prev && <Change now={s.avgBaseSalary} before={prev.avgBaseSalary} />}
                  </td>
                  <td className="num money hide-sm">{s.other ? plain(s.other) : "—"}</td>
                  <td className="num hide-sm">{s.taxRate === null ? "—" : percent(s.taxRate)}</td>
                  <td className="num money">
                    {plain(s.avgTakeHome)} {prev && <Change now={s.avgTakeHome} before={prev.avgTakeHome} />}
                  </td>
                  <td className="num money strong">{plain(s.saved)}</td>
                  <td className="num">{s.rate === null ? "—" : percent(s.rate)}</td>
                  <td className="num money hide-md">{plain(s.avgLeftover)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/* ---------- 大事记 ---------- */

function Timeline({ rows, onSelect }: { rows: Row[]; onSelect: (r: Row) => void }) {
  const items = rows.filter((r) => r.note || r.bonus || r.special).reverse();
  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>大事记</h2>
          <p>备注、奖金和特殊月</p>
        </div>
      </div>
      {items.length ? (
        <ol className="timeline">
          {items.map((r) => (
            <li key={r.id}>
              <button onClick={() => onSelect(r)}>
                <time>{monthLabel(r.month)}</time>
                <span>
                  {r.bonus > 0 && <i className="badge">奖金 <span className="money">{plain(r.bonus)}</span></i>}
                  {r.special && <i className="badge muted-badge">特殊月</i>}
                  {r.note}
                </span>
                <b className="money">{yuan(r.gross)}</b>
              </button>
            </li>
          ))}
        </ol>
      ) : (
        <p className="empty muted">还没有备注</p>
      )}
    </section>
  );
}
