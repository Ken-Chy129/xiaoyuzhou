import { useEffect, useMemo, useState } from "react";
import { anomalies, derive } from "./calc";
import { monthLabel, percent, yuan } from "./format";
import type { Entry, EntryInput, Expense } from "./types";

type NumKey = "salary" | "bonus" | "other" | "insurance" | "tax" | "family" | "fund" | "fixed" | "personal" | "fundWithdrawal";

const GROUPS: { title: string; fields: { key: NumKey; label: string; hint?: string }[] }[] = [
  {
    title: "收入",
    fields: [
      { key: "salary", label: "工资发放", hint: "税前，含奖金" },
      { key: "bonus", label: "其中奖金", hint: "年终奖等，单列方便看基本工资" },
      { key: "other", label: "附加收入", hint: "补贴、退税、房补" },
    ],
  },
  {
    title: "扣除与支出",
    fields: [
      { key: "insurance", label: "五险一金", hint: "个人缴纳部分" },
      { key: "tax", label: "个税" },
      { key: "family", label: "家庭补贴" },
      { key: "fund", label: "公积金缴存", hint: "个人 + 公司双边" },
    ],
  },
  {
    title: "存款",
    fields: [
      { key: "fixed", label: "定存" },
      { key: "personal", label: "个人存储" },
      { key: "fundWithdrawal", label: "其中公积金提取", hint: "存款里来自公积金的部分" },
    ],
  },
];

type Form = Record<NumKey, string> & { month: string; note: string; special: boolean };

const toForm = (e: Partial<Entry>, month: string): Form => ({
  month: e.month || month,
  salary: e.salary ? String(e.salary) : "",
  bonus: e.bonus ? String(e.bonus) : "",
  other: e.other ? String(e.other) : "",
  insurance: e.insurance ? String(e.insurance) : "",
  tax: e.tax ? String(e.tax) : "",
  family: e.family ? String(e.family) : "",
  fund: e.fund ? String(e.fund) : "",
  fixed: e.fixed ? String(e.fixed) : "",
  personal: e.personal ? String(e.personal) : "",
  fundWithdrawal: e.fundWithdrawal ? String(e.fundWithdrawal) : "",
  note: e.note || "",
  special: Boolean(e.special),
});

const toInput = (f: Form): EntryInput => ({
  month: f.month,
  salary: Number(f.salary) || 0,
  bonus: Number(f.bonus) || 0,
  other: Number(f.other) || 0,
  insurance: Number(f.insurance) || 0,
  tax: Number(f.tax) || 0,
  family: Number(f.family) || 0,
  fund: Number(f.fund) || 0,
  fixed: Number(f.fixed) || 0,
  personal: Number(f.personal) || 0,
  fundWithdrawal: Number(f.fundWithdrawal) || 0,
  note: f.note,
  special: f.special,
});

interface Props {
  entry: Entry | null; // null = 新建
  records: Entry[];
  expenses: Expense[];
  suggestedMonth: string;
  onClose: () => void;
  onSave: (input: EntryInput) => Promise<void>;
  onDelete: (entry: Entry) => Promise<void>;
}

export function RecordEditor({ entry, records, expenses, suggestedMonth, onClose, onSave, onDelete }: Props) {
  const template = useMemo(() => {
    if (entry) return entry;
    const last = records[records.length - 1];
    if (!last) return {};
    // 新建时沿用上月的固定项，奖金/提取/备注不沿用
    const { insurance, family, fund, fixed, salary } = last;
    return { salary: salary - last.bonus, insurance, family, fund, fixed };
  }, [entry, records]);
  const [form, setForm] = useState<Form>(() => toForm(template, suggestedMonth));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const input = toInput(form);
  const [preview] = derive([{ ...input, id: "preview" }], expenses);
  const previous = records.filter((r) => r.month < form.month).pop();
  const warn = anomalies(input, previous);
  const duplicate = records.some((r) => r.month === form.month && r.id !== entry?.id);

  const set = (key: keyof Form, value: string | boolean) => setForm((f) => ({ ...f, [key]: value }));
  const fieldError = (key: NumKey) => {
    if (key === "bonus" && input.bonus > input.salary) return "不能超过工资发放";
    if (key === "fundWithdrawal" && input.fundWithdrawal > input.fixed + input.personal) return "不能超过当月存款";
    return "";
  };
  const invalid = duplicate || !form.month || GROUPS.some((g) => g.fields.some((f) => fieldError(f.key)));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (invalid) return;
    setBusy(true);
    setError("");
    try {
      await onSave(input);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  async function remove() {
    if (!entry) return;
    setBusy(true);
    try {
      await onDelete(entry);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="drawer-layer">
      <button className="scrim" aria-label="关闭" onClick={onClose} />
      <form className="drawer" onSubmit={submit} role="dialog" aria-modal="true" aria-labelledby="editor-title">
        <header className="drawer-head">
          <div>
            <span className="eyebrow">{entry ? "编辑记录" : "记一笔"}</span>
            <h2 id="editor-title">{form.month ? monthLabel(form.month) : "选择月份"}</h2>
          </div>
          <button type="button" className="btn ghost icon" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </header>

        <div className="drawer-body">
          <div className="fields">
            <label className="field">
              <span>月份</span>
              <input type="month" value={form.month} onChange={(e) => set("month", e.target.value)} required />
              {duplicate && <em className="field-error">这个月已经有记录了</em>}
            </label>
            <label className="check">
              <input type="checkbox" checked={form.special} onChange={(e) => set("special", e.target.checked)} />
              <span>
                特殊月
                <small>离职、入职等，不计入月均</small>
              </span>
            </label>
          </div>

          {GROUPS.map((group) => (
            <fieldset key={group.title}>
              <legend>{group.title}</legend>
              <div className="fields">
                {group.fields.map((f) => (
                  <label className="field" key={f.key}>
                    <span>
                      {f.label} {f.hint && <small>{f.hint}</small>}
                    </span>
                    <input
                      inputMode="decimal"
                      type="number"
                      step="0.01"
                      min="0"
                      placeholder="0"
                      value={form[f.key]}
                      onChange={(e) => set(f.key, e.target.value)}
                    />
                    {fieldError(f.key) ? (
                      <em className="field-error">{fieldError(f.key)}</em>
                    ) : (
                      warn[f.key as keyof typeof warn] && <em className="field-hint warn">{warn[f.key as keyof typeof warn]}</em>
                    )}
                  </label>
                ))}
              </div>
            </fieldset>
          ))}

          <label className="field">
            <span>备注</span>
            <textarea rows={2} value={form.note} onChange={(e) => set("note", e.target.value)} placeholder="加班、调薪、补发……" />
          </label>

          <div className="preview">
            <div>
              <span>到手</span>
              <b className="money">{yuan(preview.takeHome)}</b>
            </div>
            <div>
              <span>可支配</span>
              <b className="money">{yuan(preview.disposable)}</b>
            </div>
            <div>
              <span>存下</span>
              <b className="money">{yuan(preview.saved)}</b>
            </div>
            <div>
              <span>储蓄率</span>
              <b>{preview.rate === null ? "—" : percent(preview.rate, 0)}</b>
            </div>
            <div>
              <span>固定开销</span>
              <b className="money">{yuan(preview.fixedCost)}</b>
            </div>
            <div>
              <span>其他花销（推算）</span>
              <b className={`money ${preview.leftover < 0 ? "down" : ""}`}>{yuan(preview.leftover)}</b>
            </div>
          </div>
          {error && <p className="form-error">{error}</p>}
        </div>

        <footer className="drawer-foot">
          {entry &&
            (confirming ? (
              <span className="confirm">
                确认删除？
                <button type="button" className="btn danger" onClick={remove} disabled={busy}>
                  删除
                </button>
                <button type="button" className="btn ghost" onClick={() => setConfirming(false)}>
                  取消
                </button>
              </span>
            ) : (
              <button type="button" className="btn ghost danger-text" onClick={() => setConfirming(true)}>
                删除
              </button>
            ))}
          <span className="spacer" />
          <button type="button" className="btn" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn primary" disabled={busy || invalid}>
            {busy ? "保存中…" : "保存"}
          </button>
        </footer>
      </form>
    </div>
  );
}
