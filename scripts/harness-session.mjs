#!/usr/bin/env node
/**
 * Inspect DeepSeek Harness sessions over its HTTP API.
 *
 *   node scripts/harness-session.mjs list [--all]
 *   node scripts/harness-session.mjs tail <sessionId> [count]
 *   node scripts/harness-session.mjs grep <sessionId> <substring>
 *   node scripts/harness-session.mjs find <substring> [--hours N]
 *   node scripts/harness-session.mjs cancel <sessionId>|--others
 *   node scripts/harness-session.mjs steer <sessionId> <text>
 *
 * **Why this exists, and why it does not read the session journal directly.**
 * The journal on disk (\`~/.dsh/sessions/<cwd>/<id>/session.v3.jsonl.zstd\`) is a
 * concatenation of zstd frames, and **most frames hold more than one record** -
 * on one real session, 270 of 413 frames did. A reader that assumes one frame
 * per record parses the single-record frames, fails on the rest, and silently
 * drops them: that mistake read 129 of 1043 records and reported the log as
 * "quiet" while it was full of activity.
 *
 * \`session/page\` returns the same log already parsed, so there is nothing to
 * get wrong here. Use it.
 *
 * Environment: DSH_ORIGIN (default http://127.0.0.1:3080), DSH_SELF (marks your
 * own session in \`list\`), DSH_LAUNCH_TOKEN (the `?token=` value the launcher
 * printed; otherwise it is read from the launcher's log).
 */
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

const ORIGIN = process.env.DSH_ORIGIN ?? "http://127.0.0.1:3080";
const SELF = process.env.DSH_SELF ?? "";

function cookieFrom(res) {
  return (res.headers.getSetCookie?.() ?? []).map((c) => c.split(";")[0]).join("; ");
}

/** The launch token the launcher printed, or undefined. */
async function launchToken() {
  if (process.env.DSH_LAUNCH_TOKEN) return process.env.DSH_LAUNCH_TOKEN;
  // dsh-launch.sh writes the server's output here, token line included, and its
  // console is the only place the harness prints one. The file is local and
  // user-owned: the launcher creates it under umask 077.
  try {
    const port = new URL(ORIGIN).port || "3080";
    const state = process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state");
    const log = await readFile(join(state, "dsh", "web." + port + ".out.log"), "utf8");
    return /\?token=([A-Za-z0-9_-]+)/.exec(log)?.[1];
  } catch {
    return undefined;
  }
}

/**
 * The token URL from an `auth.json` an older launcher published.
 *
 * Nothing writes that file any more: it is a public asset, so it signed in
 * anything that could reach the port. A harness started before the launcher was
 * updated may still be serving one, which is the only reason this remains.
 */
async function legacyTokenUrl() {
  const boot = await fetch(new URL("/auth.json", ORIGIN));
  if (!boot.ok) {
    throw new Error(
      "no launch token: set DSH_LAUNCH_TOKEN to the ?token= value the launcher printed " +
        "(auth.json is gone by design; HTTP " + boot.status + " for it)",
    );
  }
  const { urls } = await boot.json();
  const tokenUrl = urls?.[ORIGIN] ?? Object.values(urls ?? {})[0];
  if (!tokenUrl) throw new Error("auth.json names no url for " + ORIGIN);
  return tokenUrl;
}

async function authenticate() {
  const token = await launchToken();
  const exchange = token
    ? new URL("/?token=" + token, ORIGIN)
    : await legacyTokenUrl();
  const res = await fetch(exchange, { redirect: "manual" });
  const cookie = cookieFrom(res);
  if (!cookie) throw new Error("token exchange returned no cookie (HTTP " + res.status + ")");
  return cookie;
}

async function call(endpoint, field, request, cookie) {
  const res = await fetch(new URL("/api/" + endpoint, ORIGIN), {
    method: "POST",
    headers: { "content-type": "application/json", cookie },
    body: JSON.stringify({ type: "client-request", rpcId: randomUUID(), method: endpoint, payload: { args: { [field]: request } } }),
  });
  const json = await res.json();
  if (!json?.result?.ok) throw new Error(endpoint + " failed: " + JSON.stringify(json).slice(0, 400));
  return json.result.value;
}

const sessions = (cookie) => call("session/list", "_request", {}, cookie).then((v) => v.items ?? []);

/** Every event of one session, oldest first. */
async function events(cookie, sessionId, maxPages = 40) {
  const item = (await sessions(cookie)).find((s) => s.sessionId === sessionId);
  if (!item) throw new Error("no such session: " + sessionId);
  const throughSeq = item.projections?.asOfSeq;
  const seen = new Map();
  let before;
  for (let page = 0; page < maxPages; page++) {
    const request = { address: { kind: "session", sessionId }, throughSeq, maxMessages: 300 };
    if (before !== undefined) request.beforeSeq = before;
    const value = await call("session/page", "request", request, cookie);
    const records = value.records ?? [];
    if (!records.length) break;
    for (const r of records) seen.set(r.event.seq, r.event);
    before = records[0].event.seq;
    if (!value.hasMore) break;
  }
  return { item, events: [...seen.values()].sort((a, b) => a.seq - b.seq) };
}

const stamp = (ms) => new Date(ms).toISOString().replace("T", " ").slice(0, 19);

/** Assistant text is clipped to this many characters; override with DSH_TAIL_CHARS. */
const TAIL_CHARS = Number(process.env.DSH_TAIL_CHARS ?? 150);

/** One line describing an event, with the payload that matters. */
function describe(event) {
  const d = event.data ?? {};
  switch (event.type) {
    case "step/start":
      return "turn " + d.turn + " step " + d.step;
    case "tool/call":
      return d.name + "  " + String(d.arguments ?? "").slice(0, 150);
    case "tool/ptc-dispatch-start":
      return (d.name ?? "?") + "  " + String(d.arguments?.command ?? JSON.stringify(d.arguments ?? {})).replace(/\s+/g, " ").slice(0, 170);
    case "user/message": {
      const text = (d.message?.content ?? []).map((c) => c.text ?? "").join("");
      return "[" + (d.source?.kind ?? "?") + "] " + text.replace(/\s+/g, " ").slice(0, 150);
    }
    case "assistant/message": {
      const text = (d.message?.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("");
      // 150 characters is enough to see that something happened and not enough
      // to read what was said; DSH_TAIL_CHARS raises it when the answer itself
      // is the thing being looked at.
      return text ? text.replace(/\s+/g, " ").slice(0, TAIL_CHARS) : "(reasoning only)";
    }
    default:
      return JSON.stringify(d).slice(0, 150);
  }
}

function requireSession(argv, index = 0) {
  const id = argv[index];
  if (!id) {
    console.error("a session id is required");
    process.exit(2);
  }
  return id;
}

const [command, ...argv] = process.argv.slice(2);
const cookie = await authenticate();

if (command === "list" || !command) {
  const all = argv.includes("--all");
  const now = Date.now();
  const items = (await sessions(cookie)).sort((a, b) => b.updatedAt - a.updatedAt);
  const rows = all ? items : items.filter((s) => s.running);
  console.log((all ? "sessions" : "running") + ": " + rows.length + (all ? "" : " of " + items.length));
  for (const s of rows) {
    const p = s.projections?.values ?? {};
    console.log("  " + s.sessionId + (s.sessionId === SELF ? "   <- this session" : ""));
    console.log("     " + String(p.title ?? "(untitled)").slice(0, 40) +
      " | steps " + (p.sessionStats?.steps ?? "?") +
      " | idle " + Math.round((now - s.updatedAt) / 1000) + "s" +
      " | " + s.cwd);
  }
} else if (command === "tail") {
  const id = requireSession(argv);
  const count = Number(argv[1] ?? 20);
  const { item, events: all } = await events(cookie, id);
  const p = item.projections?.values ?? {};
  console.log(id + "  running=" + item.running + "  steps=" + (p.sessionStats?.steps ?? "?") + "  events=" + all.length);
  for (const e of all.slice(-count)) console.log("  " + stamp(e.time) + "  " + e.type.padEnd(24) + "  " + describe(e));
} else if (command === "grep") {
  const id = requireSession(argv);
  const needle = argv[1];
  if (!needle) {
    console.error("a substring is required");
    process.exit(2);
  }
  const { events: all } = await events(cookie, id);
  const hits = all.filter((e) => JSON.stringify(e).includes(needle));
  console.log(all.length + " events, " + hits.length + " matching " + JSON.stringify(needle));
  for (const e of hits) console.log("  seq " + e.seq + "  " + stamp(e.time) + "  " + e.type + "\n      " + describe(e));
} else if (command === "find") {
  const needle = argv[0];
  if (!needle) {
    console.error("a substring is required");
    process.exit(2);
  }
  const hours = argv.includes("--hours") ? Number(argv[argv.indexOf("--hours") + 1]) : 24;
  const cutoff = Date.now() - hours * 3600_000;
  const recent = (await sessions(cookie)).filter((s) => s.updatedAt >= cutoff);
  console.log("searching " + recent.length + " session(s) touched in the last " + hours + "h");
  for (const s of recent) {
    let found;
    try {
      found = await events(cookie, s.sessionId, 12);
    } catch {
      continue;
    }
    const hits = found.events.filter((e) => JSON.stringify(e).includes(needle));
    if (!hits.length) continue;
    console.log("  " + s.sessionId + "  (" + hits.length + " hit(s))");
    for (const e of hits.slice(0, 3)) console.log("      seq " + e.seq + "  " + stamp(e.time) + "  " + e.type + "  " + describe(e));
  }
} else if (command === "cancel") {
  // Cancelling stops the agent, never the processes it started, and a
  // background job finishing afterwards starts a fresh turn - so confirm
  // afterwards rather than assuming it took.
  const targets = argv[0] === "--others"
    ? (await sessions(cookie)).filter((s) => s.running && s.sessionId !== SELF).map((s) => s.sessionId)
    : [requireSession(argv)];
  for (const id of targets) {
    const result = await call("session/cancel", "request", { sessionId: id }, cookie);
    console.log("cancelled " + id + "  " + JSON.stringify(result));
  }
} else if (command === "steer" || command === "queue") {
  const id = requireSession(argv);
  const text = argv.slice(1).join(" ");
  if (!text) {
    console.error("text is required");
    process.exit(2);
  }
  const result = await call("session/prompt", "request", {
    requestId: randomUUID(),
    sessionId: id,
    mode: command,
    content: [{ type: "text", text }],
  }, cookie);
  console.log(command + " -> " + id + "  " + JSON.stringify(result));
} else {
  console.error("usage: list [--all] | tail <id> [n] | grep <id> <substring> | find <substring> [--hours N] | cancel <id>|--others | steer <id> <text>");
  process.exit(2);
}
