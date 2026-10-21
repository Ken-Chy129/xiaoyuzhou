/* 小宇宙待办（uTools）：看今天、新增、勾掉、标重点。数据和手机同一份。 */
const $app = document.getElementById("app");
const $toast = document.getElementById("toast");
const db = window.utools ? utools.dbStorage : { getItem: (k) => JSON.parse(localStorage.getItem(k)), setItem: (k, v) => localStorage.setItem(k, JSON.stringify(v)), removeItem: (k) => localStorage.removeItem(k) };

const state = {
  token: db.getItem("token"),
  items: db.getItem("cache") || null,
  input: "",
  sel: 0,
  adding: false,
  showSomeday: db.getItem("showSomeday") !== false,
  error: "",
  edit: null, // 正在编辑的草稿 { id, text, date, time, unit, pinned }
};

/* ---------- 时间（北京时间） ---------- */
const pad = (n) => String(n).padStart(2, "0");
const bj = () => new Date(Date.now() + 8 * 3600e3);
const today = () => bj().toISOString().slice(0, 10);
const nowStamp = () => bj().toISOString().slice(0, 16);
const WEEK = "日一二三四五六";
const parseDay = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(Date.UTC(y, m - 1, d)); };
function dayText(s) {
  const t = today();
  const diff = Math.round((parseDay(s) - parseDay(t)) / 864e5);
  if (diff === 0) return "今天";
  if (diff === 1) return "明天";
  if (diff === -1) return "昨天";
  const d = parseDay(s);
  return `${d.getUTCMonth() + 1}月${d.getUTCDate()}日 周${WEEK[d.getUTCDay()]}`;
}
function repeatText(r) {
  if (!r) return "";
  const n = r.every > 1 ? r.every : "";
  return { day: `每${n}天`, week: `每${n}周`, month: `每${n ? n + "个" : ""}月`, year: "每年" }[r.unit] || "";
}
function meta(t, section) {
  const parts = [];
  if (t.due) {
    const date = t.due.slice(0, 10), time = t.due.length > 10 ? t.due.slice(11) : "";
    if (section === "today") { if (time) parts.push(time); }
    else parts.push(dayText(date) + (time ? " " + time : ""));
  }
  const r = repeatText(t.repeat);
  if (r) parts.push(r);
  return parts.join(" · ");
}
const isLate = (t) => !t.done && t.due && (t.due.length > 10 ? t.due < nowStamp() : t.due < today());

/* ---------- 网络 ---------- */
async function api(method, path, body) {
  try {
    return await window.me.request(method, path, body, state.token);
  } catch (e) {
    if (e.status === 401 && path !== "/api/pair") { state.token = null; db.removeItem("token"); render(); }
    throw e;
  }
}
async function load() {
  if (!state.token) return;
  try {
    const d = await api("GET", "/api/todos");
    state.items = d.items;
    state.error = "";
    db.setItem("cache", d.items);
  } catch (e) {
    state.error = e.message;
  }
  if (!state.edit) render(); // 编辑时不打断输入
}
const put = (t) => { state.items = state.items.map((x) => (x.id === t.id ? t : x)); db.setItem("cache", state.items); };

/* ---------- 分组 ---------- */
function groups() {
  const t = today();
  const all = (state.items || []).filter((x) => x.kind === "todo");
  const q = state.input.trim().toLowerCase();
  const match = (x) => !q || x.text.toLowerCase().includes(q);
  const byTime = (a, b) => Number(b.pinned) - Number(a.pinned) || (a.due || "").localeCompare(b.due || "") || a.createdAt - b.createdAt;
  const open = all.filter((x) => !x.done && match(x));
  const overdue = open.filter((x) => x.due && x.due.slice(0, 10) < t).sort(byTime);
  const todayList = open.filter((x) => x.due && x.due.slice(0, 10) === t).sort(byTime);
  const someday = open.filter((x) => !x.due).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.createdAt - a.createdAt);
  const doneToday = all.filter((x) => x.done && match(x) && x.doneAt && new Date(x.doneAt + 8 * 3600e3).toISOString().slice(0, 10) === t)
    .sort((a, b) => b.doneAt - a.doneAt);
  const upcoming = open.filter((x) => x.due && x.due.slice(0, 10) > t).sort(byTime);
  const out = [];
  if (overdue.length) out.push({ key: "late", title: "之前没做完", items: overdue });
  out.push({ key: "today", title: "今天", items: todayList });
  if (someday.length) out.push({ key: "someday", title: "随时", items: someday, toggle: true });
  // 搜索时把以后的也翻出来
  if (q && upcoming.length) out.push({ key: "upcoming", title: "以后", items: upcoming });
  if (doneToday.length) out.push({ key: "done", title: "今天完成了", items: doneToday });
  return { out, upcoming: upcoming.length };
}
function visibleRows() {
  const rows = [];
  for (const g of groups().out) if (!(g.toggle && !state.showSomeday)) rows.push(...g.items);
  return rows;
}

/* ---------- 渲染 ---------- */
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function render() {
  if (!state.token) return renderPair();
  const { out, upcoming } = groups();
  const rows = visibleRows();
  if (state.sel >= rows.length) state.sel = Math.max(0, rows.length - 1);
  const selId = state.input.trim() ? null : rows[state.sel]?.id;
  const d = bj();
  let html = `<div class="head"><h1>今天</h1><span>${d.getUTCMonth() + 1}月${d.getUTCDate()}日 周${WEEK[d.getUTCDay()]}</span><span class="sp"></span>
    ${state.error ? `<span style="color:var(--late)">${esc(state.error)}</span>` : ""}
    <a data-act="web">在网页打开 ↗</a></div><div class="scroll" id="scroll">`;
  if (state.input.trim()) {
    html += `<div class="add ${state.adding ? "busy" : ""}"><span class="k">↵</span><span class="t">${esc(state.input.trim())}</span><small>${state.adding ? "添加中…" : "加到今天"}</small></div>`;
  }
  if (!state.items) html += `<div class="empty">加载中…</div>`;
  else {
    let anything = false;
    for (const g of out) {
      if (!g.items.length && g.key === "today" && state.input.trim()) continue;
      anything = anything || g.items.length > 0;
      const closed = g.toggle && !state.showSomeday;
      html += `<div class="sec ${g.key === "late" ? "late" : ""} ${g.toggle ? "toggle" : ""} ${closed ? "closed" : ""}" ${g.toggle ? 'data-act="someday"' : ""}>
        ${g.toggle ? '<span class="chev">▾</span>' : ""}${g.title}<b>${g.items.length}</b></div>`;
      if (closed) continue;
      if (!g.items.length && g.key === "today") html += `<div class="row" style="color:var(--ink3)">今天没有安排${upcoming ? ` · 以后还有 ${upcoming} 件` : ""}</div>`;
      for (const t of g.items) {
        const late = isLate(t);
        const m = meta(t, g.key);
        html += `<div class="row ${t.done ? "done" : ""} ${t.id === selId ? "sel" : ""}" data-id="${t.id}">
          <button class="check ${late ? "late" : ""}" data-act="toggle">${t.done ? "✓" : ""}</button>
          <div class="body"><span class="text">${esc(t.text)}</span>${m ? `<span class="meta ${late ? "late" : ""}">${esc(m)}</span>` : ""}</div>
          ${t.done ? "" : `<button class="star ${t.pinned ? "on" : ""}" data-act="pin" title="重点">${t.pinned ? "★" : "☆"}</button>`}
          <button class="del" data-act="del" title="删除">✕</button></div>`;
      }
    }
    if (!anything && !state.input.trim()) html += `<div class="empty"><div class="big">🪐</div>今天清空了。在上面输入就能新增</div>`;
  }
  html += `</div><div class="foot"><span><kbd>↵</kbd>新增 / 完成</span><span><kbd>↑↓</kbd>选择</span><span><kbd>→</kbd>编辑</span><span><kbd>Tab</kbd>重点</span><span><kbd>⌫</kbd>删除</span><span style="flex:1"></span><span>和手机同步</span></div>`;
  if (state.edit) html += editorHtml();
  $app.innerHTML = html;
  if (state.edit) bindEditor();
  else document.querySelector(".row.sel")?.scrollIntoView({ block: "nearest" });
}

/* ---------- 编辑 ---------- */
const addDays = (s, n) => { const d = parseDay(s); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const REPEATS = [["", "不重复"], ["day", "每天"], ["week", "每周"], ["month", "每月"], ["year", "每年"]];

function openEdit(t) {
  state.edit = {
    id: t.id, text: t.text, pinned: !!t.pinned,
    date: t.due ? t.due.slice(0, 10) : "",
    time: t.due && t.due.length > 10 ? t.due.slice(11, 16) : "",
    unit: t.repeat?.unit || "",
    saving: false,
  };
  if (window.utools) utools.removeSubInput();
  render();
  const el = document.getElementById("ed-text");
  if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
}
function closeEdit() {
  state.edit = null;
  if (window.utools) {
    setSub();
    utools.setSubInputValue(state.input);
  }
  render();
}
function editorHtml() {
  const e = state.edit, t = today();
  const pill = (on, attrs, label) => `<button class="pill ${on ? "on" : ""}" ${attrs}>${label}</button>`;
  const dates = [["今天", t], ["明天", addDays(t, 1)], ["下周", addDays(t, 7)], ["随时", ""]];
  return `<div class="mask" data-ed="close"><div class="sheet" data-ed="keep">
    <div class="ed-head"><b>编辑待办</b><span class="sp"></span><button class="x" data-ed="close" title="关闭 Esc">✕</button></div>
    <input id="ed-text" class="ed-text" value="${esc(e.text)}" placeholder="要做什么" maxlength="300" />
    <div class="ed-row"><label>日期</label><div class="pills">
      ${dates.map(([l, v]) => pill(e.date === v, `data-ed="date" data-v="${v}"`, l)).join("")}
      <input type="date" id="ed-date" value="${e.date}" class="ed-in ${e.date && !dates.some(([, v]) => v === e.date) ? "on" : ""}" />
    </div></div>
    <div class="ed-row ${e.date ? "" : "off"}"><label>时间</label><div class="pills">
      ${pill(!e.time, 'data-ed="allday"', "全天")}
      <input type="time" id="ed-time" value="${e.time}" class="ed-in ${e.time ? "on" : ""}" ${e.date ? "" : "disabled"} />
    </div></div>
    <div class="ed-row ${e.date ? "" : "off"}"><label>重复</label><div class="pills">
      ${REPEATS.map(([v, l]) => pill(e.unit === v, `data-ed="unit" data-v="${v}" ${e.date || !v ? "" : "disabled"}`, l)).join("")}
    </div></div>
    <div class="ed-row"><label>重点</label><div class="pills">${pill(e.pinned, 'data-ed="pin"', e.pinned ? "★ 重点" : "☆ 标为重点")}</div></div>
    <div class="ed-foot">
      <button class="btn danger" data-ed="del">删除</button><span class="sp"></span>
      <span class="hint"><kbd>Esc</kbd>取消</span>
      <button class="btn primary" data-ed="save" ${e.saving ? "disabled" : ""}>${e.saving ? "保存中…" : "保存 ↵"}</button>
    </div>
  </div></div>`;
}
function bindEditor() {
  const e = state.edit;
  const text = document.getElementById("ed-text");
  text.addEventListener("input", () => { e.text = text.value; });
  document.getElementById("ed-date").addEventListener("change", (ev) => {
    e.date = ev.target.value;
    if (!e.date) { e.time = ""; e.unit = ""; }
    render();
  });
  document.getElementById("ed-time").addEventListener("change", (ev) => { e.time = ev.target.value; render(); });
}
async function saveEdit() {
  const e = state.edit;
  if (!e || e.saving) return;
  const text = e.text.trim();
  if (!text) return toast("内容不能为空");
  const due = e.date ? (e.time ? `${e.date}T${e.time}` : e.date) : null;
  let repeat = null;
  if (due && e.unit) {
    const d = parseDay(e.date);
    repeat = { unit: e.unit, every: 1 };
    if (e.unit === "month" || e.unit === "year") repeat.day = d.getUTCDate();
    if (e.unit === "year") repeat.month = d.getUTCMonth() + 1;
  }
  e.saving = true;
  render();
  try {
    put(await api("PATCH", `/api/todos/${e.id}`, { kind: "todo", text, pinned: e.pinned, due, repeat }));
    closeEdit();
    toast("已保存");
  } catch (err) {
    e.saving = false;
    render();
    toast(err.message);
  }
}
function onEditorClick(ev) {
  const el = ev.target.closest("[data-ed]");
  if (!el) return;
  const act = el.dataset.ed, e = state.edit;
  if (act === "keep") return;
  if (act === "close") return closeEdit();
  if (act === "save") return saveEdit();
  if (act === "del") { const t = state.items.find((x) => x.id === e.id); closeEdit(); if (t) remove(t); return; }
  if (act === "date") { e.date = el.dataset.v; if (!e.date) { e.time = ""; e.unit = ""; } }
  if (act === "allday") e.time = "";
  if (act === "unit") e.unit = el.dataset.v;
  if (act === "pin") e.pinned = !e.pinned;
  render();
  document.getElementById("ed-text")?.focus();
}

function renderPair(err = "") {
  if (window.utools) utools.removeSubInput();
  $app.innerHTML = `<div class="pair">
    <img src="logo.png" alt="" />
    <h2>连接到小宇宙</h2>
    <p>在服务器上运行 <code>node server/cli.mjs pair</code><br/>把 6 位配对码填在这里，只需配对一次</p>
    <input id="code" maxlength="6" inputmode="numeric" placeholder="······" autofocus />
    <div class="err">${esc(err)}</div></div>`;
  const input = document.getElementById("code");
  input.focus();
  input.addEventListener("input", async () => {
    input.value = input.value.replace(/\D/g, "").slice(0, 6);
    if (input.value.length !== 6) return;
    input.disabled = true;
    try {
      const r = await window.me.request("POST", "/api/pair", { code: input.value, name: "uTools · " + window.me.hostname() });
      state.token = r.token;
      db.setItem("token", r.token);
      enter();
    } catch (e) {
      renderPair(e.message);
    }
  });
}

/* ---------- 操作 ---------- */
let toastTimer;
function toast(text, undo) {
  $toast.innerHTML = esc(text) + (undo ? ' <button id="undo">撤销</button>' : "");
  $toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $toast.classList.remove("show"), undo ? 4500 : 2200);
  if (undo) document.getElementById("undo").onclick = () => { $toast.classList.remove("show"); undo(); };
}

async function add(text) {
  text = text.trim();
  if (!text || state.adding) return;
  state.adding = true;
  render();
  try {
    // 回车就是加到今天的待办，原文不做任何识别
    const t = await api("POST", "/api/todos", { kind: "todo", text, due: today(), repeat: null, pinned: false });
    state.items = [...(state.items || []), t];
    db.setItem("cache", state.items);
    state.input = "";
    if (window.utools) utools.setSubInputValue("");
    toast("已加到今天", () => remove(t, true));
  } catch (e) {
    toast(e.message);
  } finally {
    state.adding = false;
    render();
  }
}
async function toggle(t) {
  try {
    const r = await api("POST", `/api/todos/${t.id}/${t.done ? "undone" : "done"}`, {});
    put(r);
    if (t.repeat && !t.done) toast(`下一次：${meta(r, "")}`);
  } catch (e) { toast(e.message); }
  render();
}
async function pin(t) {
  try { put(await api("PATCH", `/api/todos/${t.id}`, { pinned: !t.pinned })); } catch (e) { toast(e.message); }
  render();
}
async function remove(t, silent) {
  try {
    await api("DELETE", `/api/todos/${t.id}`);
    state.items = state.items.filter((x) => x.id !== t.id);
    db.setItem("cache", state.items);
    if (!silent) toast("已删除", async () => {
      const { id, createdAt, updatedAt, doneAt, doneCount, ...rest } = t;
      const back = await api("POST", "/api/todos", rest);
      state.items = [...state.items, back];
      render();
    });
  } catch (e) { toast(e.message); }
  render();
}

$app.addEventListener("click", (e) => {
  if (state.edit) return onEditorClick(e);
  const el = e.target.closest("[data-act]");
  const act = el?.dataset.act;
  if (!el) {
    // 点文字那一行就是编辑
    const row = e.target.closest(".row[data-id]");
    const t = row && state.items?.find((x) => x.id === row.dataset.id);
    if (t && e.target.closest(".body")) openEdit(t);
    return;
  }
  if (act === "web") return window.utools ? utools.shellOpenExternal("https://me.ken-chy129.cn/#todo") : open("https://me.ken-chy129.cn/#todo");
  if (act === "someday") { state.showSomeday = !state.showSomeday; db.setItem("showSomeday", state.showSomeday); return render(); }
  const id = el.closest(".row")?.dataset.id;
  const t = state.items?.find((x) => x.id === id);
  if (!t) return;
  if (act === "toggle") toggle(t);
  if (act === "pin") pin(t);
  if (act === "del") remove(t);
});
$app.addEventListener("mousemove", (e) => {
  if (state.edit) return;
  const row = e.target.closest(".row[data-id]");
  if (!row || state.input.trim()) return;
  const i = visibleRows().findIndex((x) => x.id === row.dataset.id);
  if (i >= 0 && i !== state.sel) { state.sel = i; document.querySelectorAll(".row.sel").forEach((r) => r.classList.remove("sel")); row.classList.add("sel"); }
});

document.addEventListener("keydown", (e) => {
  if (!state.token) return;
  if (state.edit) {
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); closeEdit(); }
    else if (e.key === "Enter" && !e.isComposing && e.target.tagName !== "BUTTON") { e.preventDefault(); saveEdit(); }
    return;
  }
  const rows = visibleRows();
  const cur = rows[state.sel];
  const typing = state.input.trim().length > 0;
  if (e.key === "Enter") {
    e.preventDefault();
    if (typing) add(state.input);
    else if (cur) toggle(cur);
  } else if (e.key === "ArrowDown" && !typing) {
    e.preventDefault(); state.sel = Math.min(rows.length - 1, state.sel + 1); render();
  } else if (e.key === "ArrowUp" && !typing) {
    e.preventDefault(); state.sel = Math.max(0, state.sel - 1); render();
  } else if (e.key === "Tab" && !typing && cur && !cur.done) {
    e.preventDefault(); pin(cur);
  } else if ((e.key === "ArrowRight" || ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "e")) && !typing && cur) {
    e.preventDefault(); openEdit(cur);
  } else if ((e.key === "Backspace" || e.key === "Delete") && !typing && cur) {
    e.preventDefault(); remove(cur);
  }
});

/* ---------- 进入 ---------- */
function setSub() {
  utools.setSubInput(({ text }) => { state.input = text; render(); }, "输入待办，回车加到今天", true);
}
function enter(payload) {
  if (!state.token) return renderPair();
  state.edit = null;
  if (window.utools) {
    setSub();
    if (payload) { utools.setSubInputValue(payload); state.input = payload; }
  }
  render();
  load();
}

if (window.utools) {
  utools.onPluginEnter(({ code, type, payload }) => {
    state.input = "";
    state.sel = 0;
    if (code === "add" && type === "over" && typeof payload === "string") {
      enter();
      add(payload);
    } else enter();
  });
  // 每次重新打开都会刷新；开着的时候每分钟刷一次（手机上可能改过）
  setInterval(() => document.visibilityState === "visible" && load(), 60e3);
} else {
  enter();
}
