// node --test server/alerts.test.mjs
import assert from "node:assert/strict";
import { test } from "node:test";
import { Alerts, diffStates, finishedAlert, waitingAlert } from "./alerts.mjs";

const s = (id, state, approvals = []) => [id, { machine: "sg2", id, name: `会话${id}`, state, approvals, lastMessage: "" }];

test("第一次看到机器只记基线", () => {
  assert.deepEqual(diffStates(null, new Map([s("a", "running")])), []);
});

test("运行 → 完成 提醒一次，再对一遍不重复", () => {
  const prev = new Map([s("a", "running")]);
  const next = new Map([s("a", "done")]);
  assert.deepEqual(diffStates(prev, next).map((x) => x.kind), ["finished"]);
  assert.deepEqual(diffStates(next, next), []);
});

test("一直是完成状态的老会话不提醒", () => {
  assert.deepEqual(diffStates(new Map(), new Map([s("old", "done")])), []);
});

test("等待批准 → 被批准后继续跑：先发等待，再发已处理", () => {
  const a = new Map([s("a", "running")]);
  const b = new Map([s("a", "waiting", [{ key: "7", kind: "command", command: "rm x" }])]);
  const c = new Map([s("a", "running")]);
  assert.deepEqual(diffStates(a, b).map((x) => x.kind), ["waiting"]);
  assert.deepEqual(diffStates(b, c).map((x) => x.kind), ["resolved"]);
});

test("连续两个批准请求各提醒一次", () => {
  const b = new Map([s("a", "waiting", [{ key: "7" }])]);
  const b2 = new Map([s("a", "waiting", [{ key: "8" }])]);
  assert.deepEqual(diffStates(b, b2).map((x) => x.kind), ["waiting"]);
});

test("完成提醒取最后一句回复，出错取错误信息", () => {
  const sess = { machine: "sg2", id: "a", name: "修 bug" };
  const done = finishedAlert(sess, { status: "completed", items: [{ type: "agentMessage", text: "第一句" }, { type: "agentMessage", text: "改好了" }] });
  assert.equal(done.kind, "done");
  assert.equal(done.text, "改好了");
  const bad = finishedAlert(sess, { status: "failed", error: { message: "额度用完" }, items: [] });
  assert.equal(bad.kind, "failed");
  assert.equal(bad.text, "额度用完");
  assert.equal(finishedAlert(sess, { status: "interrupted", items: [] }).kind, "interrupted");
});

test("等待提醒带上命令和批准用的 key", () => {
  const w = waitingAlert({ machine: "sg2", id: "a", name: "x", approvals: [{ key: "9", kind: "command", command: "npm i", reason: "装依赖" }] });
  assert.equal(w.approvalKey, "9");
  assert.equal(w.text, "npm i · 装依赖");
});

test("补发：只补序号之后的，第一次连不补历史", () => {
  const al = new Alerts(null);
  const one = al.push({ kind: "done" });
  al.push({ kind: "failed" });
  assert.equal(al.since(0).length, 0);
  assert.deepEqual(al.since(one.seq).map((x) => x.kind), ["failed"]);
});
