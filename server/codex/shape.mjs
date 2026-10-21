// 把 Codex 后台服务的会话结构整理成手机上要看的样子（见对齐结论：说的话、计划正常显示；命令折叠；改文件只给摘要；思考隐藏）。

const clip = (s, n) => {
  const t = String(s || "").trim();
  return t.length > n ? `${t.slice(0, n)}…` : t;
};
// 命令输出看结尾更有用（报错、结果通常在最后）
const tail = (s, n) => {
  const t = String(s || "").trim();
  return t.length > n ? `…${t.slice(-n)}` : t;
};

function lastAgentText(turns) {
  for (let i = turns.length - 1; i >= 0; i--) {
    const items = turns[i].items || [];
    for (let j = items.length - 1; j >= 0; j--) if (items[j].type === "agentMessage" && items[j].text) return items[j].text;
  }
  return "";
}

export function summarizeThread(machine, t, approvals = []) {
  const status = t.status || { type: "notLoaded" };
  const flags = status.activeFlags || [];
  let state = "done";
  if (approvals.length || flags.includes("waitingOnApproval") || flags.includes("waitingOnUserInput")) state = "waiting";
  else if (status.type === "active") state = "running";
  else if (status.type === "systemError") state = "error";
  const turns = t.turns || [];
  return {
    machine,
    id: t.id,
    name: t.name || clip(t.preview, 40) || "未命名会话",
    cwd: t.cwd || "",
    state, // waiting | running | done | error
    loaded: status.type !== "notLoaded",
    updatedAt: t.updatedAt || t.createdAt || 0,
    lastMessage: clip(lastAgentText(turns) || t.preview, 160),
    model: t.model || null,
    originator: t.originator || null,
    approvals: approvals.map(({ key, kind, command, cwd, reason, at }) => ({ key, kind, command, cwd, reason, at })),
  };
}

function textOfUser(item) {
  return (item.content || [])
    .map((c) => (c.type === "text" ? c.text : ""))
    .join("")
    .trim();
}

const imagesOfUser = (item) => (item.content || []).filter((c) => c.type === "image" || c.type === "localImage").length;

/** 用户消息里的图片：只给序号（图片本身另走 /image 接口按需取，避免会话 JSON 变得很大） */
const imageRefsOfUser = (item) =>
  (item.content || []).flatMap((c, i) => (c.type === "image" || c.type === "localImage" ? [{ index: i, local: c.type === "localImage" }] : []));

/** 按序号取出一张图片：内联的直接返回 data URL，本地文件的返回路径 */
export function userImage(turns, itemId, index) {
  for (const turn of turns) {
    for (const it of turn.items || []) {
      if (it.type !== "userMessage" || it.id !== itemId) continue;
      const c = (it.content || [])[index];
      if (c?.type === "image") return { url: c.url || c.image_url };
      if (c?.type === "localImage") return { path: c.path };
    }
  }
  return null;
}

export function summarizeTurns(turns) {
  return turns.map((turn) => {
    const items = [];
    for (const it of turn.items || []) {
      switch (it.type) {
        case "userMessage":
          items.push({ kind: "user", text: textOfUser(it), count: imagesOfUser(it), id: it.id, images: imageRefsOfUser(it) });
          break;
        case "agentMessage":
          if (it.text) items.push({ kind: "agent", text: it.text });
          break;
        case "plan":
          items.push({ kind: "plan", text: it.text || "" });
          break;
        case "commandExecution":
          items.push({
            kind: "command",
            command: clip(it.command, 240),
            status: it.status, // inProgress | completed | failed | declined
            exitCode: it.exitCode ?? null,
            // 折叠显示的命令输出只留结尾 300 字：它是会话数据里最大的一块，几乎不展开看
            output: tail(it.aggregatedOutput, 300),
          });
          break;
        case "fileChange": {
          const files = (it.changes || []).map((c) => c.path).filter(Boolean);
          items.push({ kind: "files", count: files.length, files: files.slice(0, 20), status: it.status });
          break;
        }
        case "mcpToolCall":
        case "dynamicToolCall":
          items.push({ kind: "tool", name: it.tool || it.name || "工具", status: it.status });
          break;
        case "webSearch":
          items.push({ kind: "tool", name: `搜索：${clip(it.query, 60)}`, status: "completed" });
          break;
        default:
          break; // reasoning 等默认隐藏
      }
    }
    // 连续的命令/工具合并成一组，手机上折叠显示
    const grouped = [];
    for (const item of items) {
      const prev = grouped.at(-1);
      if ((item.kind === "command" || item.kind === "tool") && prev?.kind === "steps") prev.steps.push(item);
      else if (item.kind === "command" || item.kind === "tool") grouped.push({ kind: "steps", steps: [item] });
      else grouped.push(item);
    }
    return {
      id: turn.id,
      status: turn.status,
      startedAt: turn.startedAt || null,
      durationMs: turn.durationMs || null,
      error: turn.error?.message || null,
      items: grouped,
    };
  });
}
