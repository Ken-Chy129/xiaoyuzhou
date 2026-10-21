#!/usr/bin/env node
// 服务器上的管理命令：
//   node server/cli.mjs pair            生成 6 位配对码
//   node server/cli.mjs devices         列出已配对设备
//   node server/cli.mjs revoke <id>     吊销设备
import { createPairingCode, listDevices, revokeDevice } from "./devices.mjs";

const [cmd, arg] = process.argv.slice(2);
if (cmd === "pair") {
  const code = createPairingCode();
  console.log(`配对码：${code.slice(0, 3)} ${code.slice(3)}（10 分钟内有效，用一次失效）`);
} else if (cmd === "devices") {
  const list = listDevices();
  if (!list.length) console.log("还没有配对的设备");
  for (const d of list) console.log(`${d.id}  ${d.name}  配对于 ${d.createdAt}  最近 ${d.lastSeenAt || "—"}`);
} else if (cmd === "revoke" && arg) {
  console.log(revokeDevice(arg) ? "已吊销" : "没有这个设备");
} else {
  console.log("用法：node server/cli.mjs pair | devices | revoke <id>");
  process.exit(1);
}
