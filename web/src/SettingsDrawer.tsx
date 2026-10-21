import { useEffect, useState } from "react";
import { monthLabel, yuan } from "./format";
import type { Expense, Settings } from "./types";

type ExpenseForm = { name: string; category: string; amount: string; from: string; to: string; note: string };
const CATEGORIES = ["居住", "交通", "通讯", "订阅", "保险", "其他"];

interface Props {
  expenses: Expense[];
  settings: Settings;
  latestMonth: string;
  onClose: () => void;
  onSaveExpense: (id: string | null, input: Omit<Expense, "id">) => Promise<void>;
  onDeleteExpense: (id: string) => Promise<void>;
  onSaveSettings: (input: Partial<Settings>) => Promise<void>;
}

export function SettingsDrawer({ expenses, settings, latestMonth, onClose, onSaveExpense, onDeleteExpense, onSaveSettings }: Props) {
  const [editing, setEditing] = useState<string | "new" | null>(null);
  const [form, setForm] = useState<ExpenseForm>({ name: "", category: "居住", amount: "", from: latestMonth, to: "", note: "" });
  const [goal, setGoal] = useState(String(settings.goal));
  const [fundAmount, setFundAmount] = useState(settings.fundBalance ? String(settings.fundBalance.amount) : "");
  const [fundAsOf, setFundAsOf] = useState(settings.fundBalance?.asOf || latestMonth);
  const [otherAssets, setOtherAssets] = useState(settings.otherAssets ? String(settings.otherAssets) : "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError("");
    try {
      await fn();
      return true;
    } catch (err) {
      setError((err as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (e: Expense | null) => {
    setEditing(e ? e.id : "new");
    setForm(
      e
        ? { name: e.name, category: e.category, amount: String(e.amount), from: e.from, to: e.to || "", note: e.note }
        : { name: "", category: "居住", amount: "", from: latestMonth, to: "", note: "" },
    );
  };

  const saveExpense = async () => {
    const ok = await run(() =>
      onSaveExpense(editing === "new" ? null : editing, {
        name: form.name,
        category: form.category,
        amount: Number(form.amount),
        from: form.from,
        to: form.to || null,
        note: form.note,
      }),
    );
    if (ok) setEditing(null);
  };

  const active = expenses.filter((e) => !e.to || e.to >= latestMonth);
  const monthlyTotal = active.reduce((s, e) => s + e.amount, 0);

  return (
    <div className="drawer-layer">
      <button className="scrim" aria-label="关闭" onClick={onClose} />
      <section className="drawer" role="dialog" aria-modal="true" aria-labelledby="settings-title">
        <header className="drawer-head">
          <div>
            <span className="eyebrow">设置</span>
            <h2 id="settings-title">固定开销与目标</h2>
          </div>
          <button type="button" className="btn ghost icon" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </header>

        <div className="drawer-body">
          <fieldset>
            <legend>每月固定开销</legend>
            <p className="hint">房租、通勤、话费这类每月差不多的支出。按生效月份自动计入每个月，不用每月重复填。</p>
            <ul className="expense-list">
              {expenses.map((e) =>
                editing === e.id ? null : (
                  <li key={e.id}>
                    <button type="button" onClick={() => startEdit(e)}>
                      <span className="tag">{e.category}</span>
                      <b>{e.name}</b>
                      <span className="muted">
                        {monthLabel(e.from)} 起{e.to ? ` · 至 ${monthLabel(e.to)}` : ""}
                      </span>
                      <span className="money num">{yuan(e.amount)}/月</span>
                    </button>
                  </li>
                ),
              )}
              {!expenses.length && editing !== "new" && <li className="empty muted">还没有固定开销</li>}
            </ul>
            {editing ? (
              <div className="expense-form">
                <div className="fields">
                  <label className="field">
                    <span>名称</span>
                    <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="房租" autoFocus />
                  </label>
                  <label className="field">
                    <span>金额 / 月</span>
                    <input type="number" inputMode="decimal" min="0" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
                  </label>
                  <label className="field">
                    <span>开始月份</span>
                    <input type="month" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} />
                  </label>
                  <label className="field">
                    <span>
                      结束月份 <small>留空表示持续</small>
                    </span>
                    <input type="month" value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} />
                  </label>
                </div>
                <div className="chips" role="radiogroup" aria-label="分类">
                  {CATEGORIES.map((c) => (
                    <button type="button" key={c} role="radio" aria-checked={form.category === c} className={form.category === c ? "chip on" : "chip"} onClick={() => setForm({ ...form, category: c })}>
                      {c}
                    </button>
                  ))}
                </div>
                <div className="row-actions">
                  {editing !== "new" && (
                    <button type="button" className="btn ghost danger-text" disabled={busy} onClick={() => run(() => onDeleteExpense(editing)).then((ok) => ok && setEditing(null))}>
                      删除
                    </button>
                  )}
                  <span className="spacer" />
                  <button type="button" className="btn" onClick={() => setEditing(null)}>
                    取消
                  </button>
                  <button type="button" className="btn primary" disabled={busy || !form.name || !Number(form.amount)} onClick={saveExpense}>
                    保存
                  </button>
                </div>
              </div>
            ) : (
              <button type="button" className="btn block" onClick={() => startEdit(null)}>
                + 添加固定开销
              </button>
            )}
            {monthlyTotal > 0 && (
              <p className="hint">
                当前每月固定开销合计 <b className="money">{yuan(monthlyTotal)}</b>
              </p>
            )}
          </fieldset>

          <fieldset>
            <legend>资产目标</legend>
            <div className="fields">
              <label className="field">
                <span>目标金额</span>
                <input type="number" min="1" value={goal} onChange={(e) => setGoal(e.target.value)} />
              </label>
              <label className="field">
                <span>
                  其他资产 <small>利息、理财收益等</small>
                </span>
                <input type="number" min="0" value={otherAssets} onChange={(e) => setOtherAssets(e.target.value)} placeholder="0" />
              </label>
              <label className="field">
                <span>
                  公积金实际余额 <small>可选，用于校准</small>
                </span>
                <input type="number" min="0" value={fundAmount} onChange={(e) => setFundAmount(e.target.value)} placeholder="按缴存估算" />
              </label>
              <label className="field">
                <span>余额截至</span>
                <input type="month" value={fundAsOf} onChange={(e) => setFundAsOf(e.target.value)} disabled={!fundAmount} />
              </label>
            </div>
            <button
              type="button"
              className="btn block"
              disabled={busy}
              onClick={() =>
                run(() =>
                  onSaveSettings({
                    goal: Number(goal),
                    otherAssets: Number(otherAssets) || 0,
                    fundBalance: fundAmount ? { amount: Number(fundAmount), asOf: fundAsOf } : null,
                  }),
                )
              }
            >
              保存目标设置
            </button>
          </fieldset>

          <fieldset>
            <legend>数据</legend>
            <p className="hint">数据保存在服务器本地文件，每次修改前自动备份。也可以随时导出一份 JSON。</p>
            <a className="btn block" href="/api/export">
              导出全部数据
            </a>
          </fieldset>
          {error && <p className="form-error">{error}</p>}
        </div>
      </section>
    </div>
  );
}
