import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "./api";

/* 待办：和手机 App、uTools 同一份数据（/api/todos）。只管待办，备忘不在这里 */

type Repeat = { unit: "day" | "week" | "month" | "year"; every?: number; day?: number; month?: number };
export type Todo = {
  id: string;
  kind: "todo" | "note";
  text: string;
  pinned: boolean;
  due: string | null;
  repeat: Repeat | null;
  done: boolean;
  doneAt?: number | null;
  createdAt: number;
  updatedAt: number;
};
type Draft = { id?: string; text: string; pinned: boolean; date: string; time: string; repeat: "" | Repeat["unit"] };

async function req<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || `请求失败（${res.status}）`, res.status);
  return data as T;
}

/* ---------- 日期（一律北京时间） ---------- */

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const bjNow = () => new Date(Date.now() + 8 * 3600_000).toISOString();
const todayStr = () => bjNow().slice(0, 10);
const parse = (s: string) => {
  const [y, m, d] = s.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d);
};
const addDays = (s: string, n: number) => {
  const d = parse(s);
  d.setDate(d.getDate() + n);
  return ymd(d);
};
const WEEK = ["日", "一", "二", "三", "四", "五", "六"];
const daysIn = (y: number, m: number) => new Date(y, m, 0).getDate();
const md = (s: string) => {
  const d = parse(s);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
};
function dueText(t: Todo, today: string) {
  if (!t.due) return "";
  const date = t.due.slice(0, 10);
  const time = t.due.length > 10 ? t.due.slice(11) : "";
  if (date === today) return time;
  const d = parse(date);
  const y = date.slice(0, 4) !== today.slice(0, 4) ? `${d.getFullYear()}年` : "";
  return `${y}${d.getMonth() + 1}月${d.getDate()}日 周${WEEK[d.getDay()]}${time ? " " + time : ""}`;
}
function repeatText(r: Repeat | null) {
  if (!r) return "";
  const n = r.every && r.every > 1 ? r.every : "";
  return { day: `每${n}天`, week: `每${n}周`, month: `每${n ? n + "个" : ""}月`, year: "每年" }[r.unit];
}

/** 重复事项会不会落在某天（只往后推，和 App 的算法一致） */
function occursOn(t: Todo, day: string) {
  const r = t.repeat;
  if (!r || !t.due) return false;
  const first = t.due.slice(0, 10);
  if (day < first) return false;
  if (day === first) return true;
  const every = Math.max(1, r.every || 1);
  const a = parse(first);
  const b = parse(day);
  const days = Math.round((b.getTime() - a.getTime()) / 86400_000);
  switch (r.unit) {
    case "day":
      return days % every === 0;
    case "week":
      return days % (7 * every) === 0;
    case "month": {
      const months = (b.getFullYear() - a.getFullYear()) * 12 + b.getMonth() - a.getMonth();
      const anchor = r.day || a.getDate();
      return months % every === 0 && b.getDate() === Math.min(anchor, daysIn(b.getFullYear(), b.getMonth() + 1));
    }
    case "year": {
      const mo = r.month || a.getMonth() + 1;
      const anchor = r.day || a.getDate();
      return (b.getFullYear() - a.getFullYear()) % every === 0 && b.getMonth() + 1 === mo && b.getDate() === Math.min(anchor, daysIn(b.getFullYear(), mo));
    }
  }
}
const byOrder = (a: Todo, b: Todo) => Number(b.pinned) - Number(a.pinned) || (a.due || "").localeCompare(b.due || "") || a.createdAt - b.createdAt;
/** 某天的待办（含落在这天的重复事项预览） */
const onDay = (items: Todo[], day: string) =>
  items.filter((t) => t.due?.slice(0, 10) === day || (!t.done && occursOn(t, day)));
const doneOn = (t: Todo) => (t.doneAt ? new Date(t.doneAt + 8 * 3600_000).toISOString().slice(0, 10) : "");

/* ---------- 页面 ---------- */

export function Todos({ onLogout, nav }: { onLogout: () => void; nav: React.ReactNode }) {
  const [items, setItems] = useState<Todo[] | null>(null);
  const [error, setError] = useState("");
  const [toast, setToast] = useState<{ text: string; undo?: () => void } | null>(null);
  const [today, setToday] = useState(todayStr);
  const [day, setDay] = useState(today);
  const [month, setMonth] = useState(today.slice(0, 7));
  const [editing, setEditing] = useState<Draft | null>(null);
  const [showDone, setShowDone] = useState(true);
  const [showSomeday, setShowSomeday] = useState(true);
  const [fresh, setFresh] = useState<string | null>(null); // 刚加的那条，做一下入场高亮
  const inputRef = useRef<HTMLInputElement>(null);

  const fail = useCallback(
    (err: unknown) => {
      if (err instanceof ApiError && err.status === 401) onLogout();
      else setToast({ text: (err as Error).message });
    },
    [onLogout],
  );
  const load = useCallback(() => {
    setToday(todayStr());
    req<{ items: Todo[] }>("/api/todos").then(
      (d) => { setItems(d.items.filter((t) => t.kind === "todo")); setError(""); },
      (e) => (e instanceof ApiError && e.status === 401 ? onLogout() : setError((e as Error).message)),
    );
  }, [onLogout]);
  useEffect(() => {
    load();
    // 回到这个标签页时刷新（手机上可能改过），挂着不动也每分钟同步一次
    const on = () => document.visibilityState === "visible" && load();
    document.addEventListener("visibilitychange", on);
    const timer = setInterval(on, 60_000);
    return () => { document.removeEventListener("visibilitychange", on); clearInterval(timer); };
  }, [load]);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), toast.undo ? 4500 : 2400);
    return () => clearTimeout(t);
  }, [toast]);
  // 按 N 或 / 直接聚焦输入框，T 回到今天
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (editing || el.closest("input, textarea, select") || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === "n" || e.key === "/") { e.preventDefault(); inputRef.current?.focus(); }
      if (e.key === "t") { setDay(today); setMonth(today.slice(0, 7)); }
    };
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [editing, today]);

  const put = (t: Todo) => setItems((xs) => (xs || []).map((x) => (x.id === t.id ? t : x)));
  const toggle = async (t: Todo) => {
    put({ ...t, done: !t.done, doneAt: t.done ? null : Date.now() }); // 先给反馈，再等服务器
    try {
      const r = await req<Todo>(`/api/todos/${t.id}/${t.done ? "undone" : "done"}`, "POST", {});
      put(r);
      if (t.repeat && !t.done) setToast({ text: `下一次：${dueText(r, today) || "今天"}` });
    } catch (e) { put(t); fail(e); }
  };
  const pin = async (t: Todo) => {
    put({ ...t, pinned: !t.pinned });
    try { put(await req<Todo>(`/api/todos/${t.id}`, "PATCH", { pinned: !t.pinned })); } catch (e) { put(t); fail(e); }
  };
  const restore = async (t: Todo) => {
    try {
      const r = await req<Todo>("/api/todos", "POST", { kind: "todo", text: t.text, pinned: t.pinned, due: t.due, repeat: t.repeat });
      setItems((xs) => [...(xs || []), r]);
    } catch (e) { fail(e); }
  };
  const remove = async (t: Todo) => {
    try {
      await req(`/api/todos/${t.id}`, "DELETE");
      setItems((xs) => (xs || []).filter((x) => x.id !== t.id));
      setEditing(null);
      setToast({ text: "已删除", undo: () => restore(t) });
    } catch (e) { fail(e); }
  };
  const save = async (d: Draft) => {
    const due = d.date ? d.date + (d.time ? `T${d.time}` : "") : null;
    const base = parse(d.date || today);
    const repeat: Repeat | null = due && d.repeat
      ? { unit: d.repeat, every: 1, ...(d.repeat === "month" ? { day: base.getDate() } : {}), ...(d.repeat === "year" ? { day: base.getDate(), month: base.getMonth() + 1 } : {}) }
      : null;
    const body = { kind: "todo", text: d.text.trim(), pinned: d.pinned, due, repeat };
    try {
      if (d.id) put(await req<Todo>(`/api/todos/${d.id}`, "PATCH", body));
      else {
        const t = await req<Todo>("/api/todos", "POST", body);
        setItems((xs) => [...(xs || []), t]);
        setFresh(t.id);
      }
      setEditing(null);
    } catch (e) { fail(e); }
  };

  // 回车：原样加到正在看的那天（默认今天）
  const [quick, setQuick] = useState("");
  const [adding, setAdding] = useState(false);
  const quickAdd = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = quick.trim();
    if (!text || adding) return;
    setAdding(true);
    try {
      const t = await req<Todo>("/api/todos", "POST", { kind: "todo", text, pinned: false, due: day, repeat: null });
      setItems((xs) => [...(xs || []), t]);
      setFresh(t.id);
      setQuick("");
    } catch (err) { fail(err); }
    setAdding(false);
  };

  const openEditor = (t: Todo) =>
    setEditing({ id: t.id, text: t.text, pinned: t.pinned, date: t.due?.slice(0, 10) || "", time: t.due && t.due.length > 10 ? t.due.slice(11) : "", repeat: t.repeat?.unit || "" });
  const goDay = (s: string) => { setDay(s); setMonth(s.slice(0, 7)); };

  const all = items || [];
  const open = all.filter((t) => !t.done);
  const isToday = day === today;
  const overdue = isToday ? open.filter((t) => t.due && t.due.slice(0, 10) < today).sort(byOrder) : [];
  const dayItems = onDay(all, day);
  const dayOpen = dayItems.filter((t) => !t.done).sort(byOrder);
  // 「完成了」：这天到期并完成的，今天还包括今天顺手完成的其他事
  const dayDone = all
    .filter((t) => t.done && (t.due?.slice(0, 10) === day || (isToday && doneOn(t) === today)))
    .sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0));
  const someday = open.filter((t) => !t.due).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.createdAt - a.createdAt);
  const upcoming = open.filter((t) => t.due && t.due.slice(0, 10) > today).sort(byOrder).slice(0, 6);

  const total = dayOpen.length + dayDone.length + overdue.length;
  const doneCount = dayDone.length;
  const pct = total ? doneCount / total : 0;
  const d = parse(day);
  const rel = isToday ? "今天" : day === addDays(today, 1) ? "明天" : day === addDays(today, -1) ? "昨天" : "";

  return (
    <>
      <header className="top">
        <div className="brand">
          <img className="mark-img" src="/logo.png" alt="" />
          <div>
            <strong>小宇宙</strong>
            <span>待办和手机同步，到点提醒</span>
          </div>
        </div>
        {nav}
        <div className="top-actions">
          <button className="btn ghost hide-sm" onClick={() => fetch("/api/logout", { method: "POST", credentials: "same-origin" }).finally(onLogout)}>退出</button>
        </div>
      </header>

      <main className="page td-page">
        {error && (
          <div className="banner">
            <span>{error}</span>
            <button className="btn" onClick={load}>重试</button>
          </div>
        )}
        {!items ? (
          !error && <div className="skeleton" />
        ) : (
          <div className="td-grid">
            <aside className="td-side">
              <Calendar items={all} month={month} setMonth={setMonth} day={day} setDay={setDay} today={today} />

              {upcoming.length > 0 && (
                <section className="td-card">
                  <h3 className="td-cap">接下来</h3>
                  <ul className="td-mini">
                    {upcoming.map((t) => (
                      <li key={t.id}>
                        <button onClick={() => goDay(t.due!.slice(0, 10))}>
                          <span className="td-mini-date">{md(t.due!)}</span>
                          <span className="td-mini-text">{t.text}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </aside>

            <section className="td-main" key={day}>
              <div className="td-head">
                <div className="td-date">
                  <span className="td-daynum">{d.getDate()}</span>
                  <div>
                    <h1>{rel || `周${WEEK[d.getDay()]}`}</h1>
                    <p>{d.getFullYear()}年{d.getMonth() + 1}月{rel ? ` · 周${WEEK[d.getDay()]}` : ""}</p>
                  </div>
                </div>
                <div className="td-head-right">
                  {total > 0 && (
                    <div className="td-progress" title={`${doneCount}/${total} 已完成`}>
                      <Ring pct={pct} />
                      <div>
                        <b>{doneCount}<small> / {total}</small></b>
                        <span>{pct === 1 ? "全部完成" : "已完成"}</span>
                      </div>
                    </div>
                  )}
                  <div className="td-nav">
                    <button className="btn ghost icon" onClick={() => goDay(addDays(day, -1))} aria-label="前一天">‹</button>
                    <button className="btn ghost" disabled={isToday} onClick={() => goDay(today)}>今天</button>
                    <button className="btn ghost icon" onClick={() => goDay(addDays(day, 1))} aria-label="后一天">›</button>
                  </div>
                </div>
              </div>

              <form className={`td-add ${adding ? "busy" : ""}`} onSubmit={quickAdd}>
                <span className="td-add-plus" aria-hidden>+</span>
                <input
                  ref={inputRef}
                  value={quick}
                  onChange={(e) => setQuick(e.target.value)}
                  placeholder={`添加到${rel || md(day)}，回车保存`}
                  autoFocus
                />
                {quick.trim() ? <kbd>↵</kbd> : <kbd className="hint">N</kbd>}
              </form>

              {overdue.length > 0 && (
                <Group title="之前没做完" count={overdue.length} tone="late">
                  <TodoList items={overdue} today={today} fresh={fresh} onToggle={toggle} onOpen={openEditor} onPin={pin} showDate />
                </Group>
              )}

              {dayOpen.length > 0 ? (
                <Group title={overdue.length ? rel || md(day) : ""} count={overdue.length ? dayOpen.length : 0}>
                  <TodoList items={dayOpen} today={today} day={day} fresh={fresh} onToggle={toggle} onOpen={openEditor} onPin={pin} />
                </Group>
              ) : (
                overdue.length === 0 && (
                  <div className="td-empty">
                    <div className="td-empty-art" aria-hidden>
                      <i /><i /><i />
                    </div>
                    <p>{dayDone.length ? "这天的都做完了" : day < today ? "这天没有记录" : "还没有安排"}</p>
                    {day >= today && <span>在上面输入，回车就加好了</span>}
                  </div>
                )
              )}

              {dayDone.length > 0 && (
                <Group title="完成了" count={dayDone.length} collapsed={!showDone} onToggle={() => setShowDone((v) => !v)}>
                  <TodoList items={dayDone} today={today} fresh={fresh} onToggle={toggle} onOpen={openEditor} onPin={pin} showDate={!isToday} />
                </Group>
              )}

              {isToday && someday.length > 0 && (
                <Group title="随时" count={someday.length} collapsed={!showSomeday} onToggle={() => setShowSomeday((v) => !v)}>
                  <TodoList items={someday} today={today} fresh={fresh} onToggle={toggle} onOpen={openEditor} onPin={pin} />
                </Group>
              )}
            </section>
          </div>
        )}
      </main>

      {editing && <Editor draft={editing} today={today} onClose={() => setEditing(null)} onSave={save} onDelete={editing.id ? () => remove(all.find((t) => t.id === editing.id)!) : undefined} />}
      {toast && (
        <div className="toast td-toast" role="status">
          {toast.text}
          {toast.undo && <button onClick={() => { toast.undo!(); setToast(null); }}>撤销</button>}
        </div>
      )}
    </>
  );
}

function Ring({ pct }: { pct: number }) {
  const r = 15;
  const c = 2 * Math.PI * r;
  return (
    <svg className="td-ring" viewBox="0 0 36 36" aria-hidden>
      <circle cx="18" cy="18" r={r} className="bg" />
      <circle cx="18" cy="18" r={r} className="fg" strokeDasharray={c} strokeDashoffset={c * (1 - pct)} />
    </svg>
  );
}

function Group({ title, count, tone, collapsed, onToggle, children }: {
  title: string; count: number; tone?: "late"; collapsed?: boolean; onToggle?: () => void; children: React.ReactNode;
}) {
  return (
    <div className={`td-group ${tone || ""} ${collapsed ? "collapsed" : ""}`}>
      {title && (
        <button type="button" className="td-group-h" onClick={onToggle} disabled={!onToggle}>
          {onToggle && <i className="td-caret" aria-hidden>›</i>}
          <span>{title}</span>
          {count > 0 && <em>{count}</em>}
        </button>
      )}
      {!collapsed && children}
    </div>
  );
}

function Calendar({ items, month, setMonth, day, setDay, today }: {
  items: Todo[]; month: string; setMonth: (m: string) => void; day: string; setDay: (d: string) => void; today: string;
}) {
  const [y, m] = month.split("-").map(Number);
  const lead = (new Date(y, m - 1, 1).getDay() + 6) % 7; // 周一开头
  const n = daysIn(y, m);
  const shift = (k: number) => {
    const d = new Date(y, m - 1 + k, 1);
    setMonth(`${d.getFullYear()}-${pad(d.getMonth() + 1)}`);
  };
  const marks = useMemo(() => {
    const out: Record<string, { open: number; all: number }> = {};
    for (let i = 1; i <= n; i++) {
      const s = `${month}-${pad(i)}`;
      const l = onDay(items, s);
      if (l.length) out[s] = { open: l.filter((t) => !t.done).length, all: l.length };
    }
    return out;
  }, [items, month, n]);
  return (
    <section className="td-card td-cal">
      <div className="td-cal-head">
        <h2>{y}年<b>{m}月</b></h2>
        <div>
          <button className="btn ghost icon" onClick={() => shift(-1)} aria-label="上个月">‹</button>
          <button className="btn ghost icon" onClick={() => shift(1)} aria-label="下个月">›</button>
        </div>
      </div>
      <div className="td-cal-grid">
        {["一", "二", "三", "四", "五", "六", "日"].map((w) => <span key={w} className="td-cal-w">{w}</span>)}
        {Array.from({ length: lead }, (_, i) => <span key={`e${i}`} />)}
        {Array.from({ length: n }, (_, i) => {
          const s = `${month}-${pad(i + 1)}`;
          const mk = marks[s];
          const cls = ["td-cal-d", s === day && "sel", s === today && "today", s < today && "past"].filter(Boolean).join(" ");
          const dot = mk ? (mk.open ? (s < today ? "late" : "open") : "done") : "";
          return (
            <button key={s} className={cls} onClick={() => setDay(s)} title={mk ? `${mk.open} 件未完成 · 共 ${mk.all} 件` : undefined}>
              <span>{i + 1}</span>
              <i className={`td-dot ${dot}`} />
            </button>
          );
        })}
      </div>
    </section>
  );
}

function TodoList({ items, today, day, fresh, showDate, onToggle, onOpen, onPin }: {
  items: Todo[]; today: string; day?: string; fresh: string | null; showDate?: boolean;
  onToggle: (t: Todo) => void; onOpen: (t: Todo) => void; onPin: (t: Todo) => void;
}) {
  const now = bjNow().slice(0, 16);
  return (
    <ul className="td-list">
      {items.map((t) => {
        const late = !t.done && !!t.due && (t.due.length > 10 ? t.due < now : t.due < today);
        const preview = !!day && t.due?.slice(0, 10) !== day; // 重复事项在以后某天的预览：那天不能勾
        const time = t.due && t.due.length > 10 ? t.due.slice(11) : "";
        const when = showDate ? dueText(t, today) : time;
        return (
          <li key={t.id} className={`td-item ${t.done ? "done" : ""} ${t.pinned ? "pinned" : ""} ${fresh === t.id ? "fresh" : ""}`}>
            <button className={`td-check ${late ? "late" : ""}`} disabled={preview} onClick={() => onToggle(t)} aria-label={t.done ? "取消完成" : "完成"} title={preview ? "重复事项，到那天再勾" : undefined}>
              <svg viewBox="0 0 16 16" aria-hidden><path d="M3.5 8.5l3 3 6-7" /></svg>
            </button>
            <button className="td-body" onClick={() => onOpen(t)}>
              <span className="td-text">{t.text}</span>
              {(when || t.repeat) && (
                <span className="td-meta">
                  {when && <span className={`td-tag ${late ? "late" : ""}`}>{when}{late ? " · 已过" : ""}</span>}
                  {t.repeat && <span className="td-tag">↻ {repeatText(t.repeat)}</span>}
                </span>
              )}
            </button>
            {!t.done && (
              <button className={`td-star ${t.pinned ? "on" : ""}`} onClick={() => onPin(t)} aria-label={t.pinned ? "取消重点" : "标为重点"} title={t.pinned ? "取消重点" : "标为重点"}>
                <svg viewBox="0 0 16 16" aria-hidden><path d="M8 1.8l1.9 3.9 4.3.6-3.1 3 .7 4.3L8 11.6l-3.8 2 .7-4.3-3.1-3 4.3-.6z" /></svg>
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function Editor({ draft, today, onClose, onSave, onDelete }: { draft: Draft; today: string; onClose: () => void; onSave: (d: Draft) => void; onDelete?: () => void }) {
  const [d, setD] = useState(draft);
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
  }, []);
  useEffect(() => {
    const on = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [onClose]);
  const set = (p: Partial<Draft>) => setD((x) => ({ ...x, ...p }));
  const submit = (e?: React.FormEvent) => { e?.preventDefault(); if (d.text.trim()) onSave(d); };
  const quickDates: [string, string][] = [[today, "今天"], [addDays(today, 1), "明天"], [addDays(today, 7), "下周"], ["", "随时"]];
  return (
    <div className="td-modal-layer">
      <button className="scrim" type="button" aria-label="关闭" onClick={onClose} />
      <form className="td-modal" onSubmit={submit}>
        <textarea
          ref={ref} rows={3} value={d.text} onChange={(e) => set({ text: e.target.value })}
          placeholder="要做什么"
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submit(); } }}
        />
        <div className="td-modal-row">
          <span className="td-label">日期</span>
          <div className="td-pills">
            {quickDates.map(([v, l]) => (
              <button type="button" key={l} className={d.date === v ? "on" : ""} onClick={() => set({ date: v, ...(v ? {} : { time: "", repeat: "" }) })}>{l}</button>
            ))}
            <input type="date" className={d.date && !quickDates.some(([v]) => v === d.date) ? "on" : ""} value={d.date} onChange={(e) => set({ date: e.target.value, ...(e.target.value ? {} : { time: "", repeat: "" }) })} />
          </div>
        </div>
        {d.date && (
          <>
            <div className="td-modal-row">
              <span className="td-label">时间</span>
              <div className="td-pills">
                <button type="button" className={!d.time ? "on" : ""} onClick={() => set({ time: "" })}>全天</button>
                <input type="time" className={d.time ? "on" : ""} value={d.time} onChange={(e) => set({ time: e.target.value })} />
                {d.time && <small className="muted">到点手机提醒</small>}
              </div>
            </div>
            <div className="td-modal-row">
              <span className="td-label">重复</span>
              <div className="td-pills">
                {([["", "不重复"], ["day", "每天"], ["week", "每周"], ["month", "每月"], ["year", "每年"]] as const).map(([v, l]) => (
                  <button type="button" key={v} className={d.repeat === v ? "on" : ""} onClick={() => set({ repeat: v })}>{l}</button>
                ))}
              </div>
            </div>
          </>
        )}
        <div className="td-modal-row">
          <span className="td-label">重点</span>
          <div className="td-pills">
            <button type="button" className={d.pinned ? "on star" : ""} onClick={() => set({ pinned: !d.pinned })}>★ {d.pinned ? "已标为重点" : "标为重点"}</button>
          </div>
        </div>
        <div className="td-modal-foot">
          {onDelete && <button type="button" className="btn ghost danger-text" onClick={onDelete}>删除</button>}
          <span style={{ flex: 1 }} />
          <span className="muted hide-sm td-keyhint"><kbd>Esc</kbd> 取消 <kbd>↵</kbd> 保存</span>
          <button type="button" className="btn ghost" onClick={onClose}>取消</button>
          <button className="btn primary" disabled={!d.text.trim()}>{d.id ? "保存" : "添加"}</button>
        </div>
      </form>
    </div>
  );
}
