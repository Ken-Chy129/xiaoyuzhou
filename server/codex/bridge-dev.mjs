// 开发用：在本机把 Codex 控制 socket 桥接到 me 服务（生产中这件事由 agentdeck 程序完成）。
// 用法：ME_URL=ws://127.0.0.1:8797/codex/machine ME_MACHINE_TOKEN=xxx node server/codex/bridge-dev.mjs
import { homedir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const SOCK = process.env.CODEX_SOCK || join(process.env.CODEX_HOME || join(homedir(), ".codex"), "app-server-control", "app-server-control.sock");
const up = new WebSocket(process.env.ME_URL, { headers: { Authorization: `Bearer ${process.env.ME_MACHINE_TOKEN}` } });

up.on("open", () => {
  // Codex 控制 socket 上跑的是 WebSocket；用 socketPath 让 ws 走 unix socket
  const local = new WebSocket("ws+unix://" + SOCK + ":/", { perMessageDeflate: false });
  const queue = [];
  local.on("open", () => {
    console.log("[bridge] 已连接本机 Codex 与 me 服务");
    for (const m of queue.splice(0)) local.send(m);
  });
  up.on("message", (d) => (local.readyState === 1 ? local.send(String(d)) : queue.push(String(d))));
  local.on("message", (d) => up.readyState === 1 && up.send(String(d)));
  local.on("close", () => up.close());
  local.on("error", (e) => {
    console.error("[bridge] 本机 Codex:", e.message);
    up.close();
  });
});
up.on("close", () => {
  console.log("[bridge] 断开");
  process.exit(0);
});
up.on("error", (e) => console.error("[bridge] me:", e.message));
