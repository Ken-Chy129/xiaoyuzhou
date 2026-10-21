import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

/* 发了新版本就自动换上：切回标签页 / 每 5 分钟比对一次首页引用的脚本 */
const current = (document.querySelector('script[type="module"][src*="/assets/"]') as HTMLScriptElement | null)?.src;
async function checkVersion() {
  if (!current) return; // 开发模式
  try {
    const html = await fetch("/", { cache: "no-store", credentials: "same-origin" }).then((r) => r.text());
    const latest = html.match(/\/assets\/index-[\w-]+\.js/)?.[0];
    if (latest && !current.endsWith(latest)) location.reload();
  } catch {
    /* 离线时忽略 */
  }
}
document.addEventListener("visibilitychange", () => document.visibilityState === "visible" && checkVersion());
window.addEventListener("focus", checkVersion);
setInterval(checkVersion, 5 * 60_000);
addEventListener("pageshow", (e) => (e as PageTransitionEvent).persisted && checkVersion());
