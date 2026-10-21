// 一句话 → 待办结构，交给大模型理解（时间、重复、是不是备忘、是否重点），结果再过一遍 todos.mjs 的校验。
// 模型走 OpenAI 兼容接口：ME_LLM_BASE_URL / ME_LLM_API_KEY / ME_LLM_MODEL
import { nowLocal } from "./todos.mjs";

const WEEK = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

function prompt(ms) {
  const { stamp } = nowLocal(ms);
  const wd = WEEK[new Date(ms + 8 * 3600_000).getUTCDay()];
  return `你是待办助手，把用户随口说的一句话整理成 JSON。现在是北京时间 ${stamp.replace("T", " ")}（${wd}）。

输出且只输出一个 JSON 对象：
{
  "kind": "todo" | "note",      // 要去做的事是 todo；只是记下的信息（地址、密码提示、想法、某个配置）是 note
  "text": string,               // 事情本身，去掉时间词和「提醒我/记得/帮我记一下」等口头语，保持用户原意，简洁
  "due": string | null,         // 何时做："YYYY-MM-DD"（只说了日期）或 "YYYY-MM-DDTHH:mm"（说了具体时间）；没提时间就是 null
  "repeat": null | { "unit": "day"|"week"|"month"|"year", "every": number, "day"?: number, "month"?: number },
  "pinned": boolean             // 用户强调重要/务必/千万别忘 时为 true
}

规则：
- 「早上」默认 08:00，「上午」09:00，「中午」12:00，「下午」15:00，「傍晚」18:00，「晚上」20:00；只说「三点」按常理判断上下午。
- 只说了时间没说日期：这个时间今天还没过就是今天，否则明天。
- 「周X」指本周或之后最近的那天；「下周X」是下一个自然周；「下下周X」再往后一周。
- 重复事项必须给 due，取第一次发生的时间（今天的时间已过就取下一次）；按月给 day，按年给 month 和 day。
- 「月底」取当月最后一天；「月初」取 1 号。
- note 的 due 和 repeat 为 null。
- 不要编造用户没说的内容。`;
}

function config() {
  return {
    base: (process.env.ME_LLM_BASE_URL || process.env.OPENAI_BASE_URL || "").replace(/\/$/, ""),
    key: process.env.ME_LLM_API_KEY || process.env.OPENAI_API_KEY || "",
    model: process.env.ME_LLM_MODEL || "gpt-5.6-luna",
  };
}

export const parserAvailable = () => Boolean(config().base && config().key);

function extractJson(s) {
  const t = String(s || "").trim().replace(/^```(?:json)?\s*|\s*```$/g, "");
  const i = t.indexOf("{");
  const j = t.lastIndexOf("}");
  if (i < 0 || j < i) throw new Error("模型没有返回 JSON");
  return JSON.parse(t.slice(i, j + 1));
}

const DUE = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$/;

/** 只保留形状正确的字段；深层校验交给 todos.mjs */
export function normalize(raw, original) {
  const out = { kind: raw.kind === "note" ? "note" : "todo", text: String(raw.text || "").trim() || original.trim() };
  out.pinned = raw.pinned === true;
  if (out.kind === "todo") {
    out.due = typeof raw.due === "string" && DUE.test(raw.due) ? raw.due : null;
    const r = raw.repeat;
    if (out.due && r && ["day", "week", "month", "year"].includes(r.unit)) {
      const rep = { unit: r.unit, every: Number.isInteger(r.every) && r.every >= 1 && r.every <= 99 ? r.every : 1 };
      if (Number.isInteger(r.day) && r.day >= 1 && r.day <= 31) rep.day = r.day;
      if (Number.isInteger(r.month) && r.month >= 1 && r.month <= 12) rep.month = r.month;
      out.repeat = rep;
    } else out.repeat = null;
  } else {
    out.due = null;
    out.repeat = null;
  }
  return out;
}

export async function parseTodo(text, ms = Date.now()) {
  const input = String(text || "").trim();
  if (!input) throw Object.assign(new Error("内容不能为空"), { status: 400 });
  if (input.length > 1000) throw Object.assign(new Error("内容太长"), { status: 400 });
  const { base, key, model } = config();
  if (!base || !key) throw Object.assign(new Error("服务器没有配置大模型"), { status: 503 });
  const res = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model,
      temperature: 0,
      messages: [
        { role: "system", content: prompt(ms) },
        { role: "user", content: input },
      ],
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) throw Object.assign(new Error(`识别服务出错（${res.status}）`), { status: 502 });
  const data = await res.json();
  return normalize(extractJson(data.choices?.[0]?.message?.content), input);
}
