/**
 * Publish sanitized copies of the workspace skills.
 *
 * The live skills in `.dsh/skills/` carry real machine addresses, credentials
 * and device identifiers, because that is what the agent needs to actually
 * drive the hardware. They are deliberately gitignored.
 *
 * This writes a shareable copy of each one to `docs/skills/`, with every real
 * value substituted for a placeholder or a documentation-range address
 * (RFC 5737 for LANs, 100.64.0.0/10 for a tailnet). The substitution map lives
 * in `.dsh/publish-map.json`, which is gitignored for the obvious reason - so
 * this script is safe to commit and the secrets never enter the repository.
 *
 * The map can only remove what it already knows about, so every sanitized copy
 * is also scanned for address- and credential-shaped content with patterns that
 * do not consult the map. Anything found is reported as a kind and a line
 * number - never the value - and nothing is written: an address that is new in
 * a skill must not reach this public repository under a clean verdict.
 *
 * Usage: node scripts/publish-skills.mjs
 */
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const SKILLS = join(ROOT, ".dsh", "skills");
const MAP_FILE = join(ROOT, ".dsh", "publish-map.json");
const OUT = join(ROOT, "docs", "skills");

const map = JSON.parse(readFileSync(MAP_FILE, "utf8"));

/** Longest key first, so a shorter address cannot clobber a longer one. */
const keys = Object.keys(map).sort((a, b) => b.length - a.length);

function sanitize(text) {
  let out = text;
  for (const key of keys) {
    // Word boundaries keep 192.0.2.3 from matching inside 192.0.2.30.
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    out = out.replace(new RegExp("\\b" + escaped + "\\b", "g"), map[key]);
  }
  return out;
}

/**
 * Address- and credential-shaped content, independent of the substitution map:
 * a value the map does not know is exactly the value the map cannot remove.
 * Loopback, the documentation ranges and the placeholders themselves stay
 * allowed, because those are what a published copy is supposed to contain.
 */
const LEAK_PATTERNS = [
  {
    kind: "IPv4 address",
    regex: /\b(?:\d{1,3}\.){3}\d{1,3}\b/g,
    allow: (value, line, index) =>
      /^(?:127\.|0\.0\.0\.0$|255\.255\.255\.255$)/.test(value) ||
      /^(?:192\.0\.2\.|198\.51\.100\.|203\.0\.113\.)/.test(value) ||
      (value === "100.64.0.0" && line.startsWith("/10", index + value.length)),
  },
  {
    kind: "IPv6 address",
    regex:
      /\b(?:[0-9a-fA-F]{1,4}:){2,7}:(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6})?\b|\b::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\b|\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b/g,
    allow: (value) => value === "::1" || /^2001:db8:/i.test(value),
  },
  {
    // The bare twelve-hex form is deliberately not matched: it is
    // indistinguishable from a UUID or a commit fragment.
    kind: "MAC address",
    regex: /\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b|\b[0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5}\b/g,
  },
  {
    kind: "host and port",
    // adb prose writes the protocol in front of the port, and the loopback
    // socket is part of the interface the skill documents.
    regex: /(?<![<\w.-])[A-Za-z][A-Za-z0-9.-]*:\d{2,5}\b/g,
    allow: (value) => /^(?:localhost|tcp|udp):/i.test(value),
  },
  {
    kind: "credential-shaped token",
    regex: /\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}|tskey-[A-Za-z0-9-]{10,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})\b/g,
  },
  {
    kind: "credential assignment",
    regex: /(?<![\w-])(?:token|password|passwd|secret|api[_-]?key|apikey)(?![\w-])\s*[=:]\s*[A-Za-z0-9._~+/=:@-]{12,}/gi,
  },
  {
    kind: "bearer token",
    regex: /\bBearer\s+[A-Za-z0-9._~+/=-]{16,}/g,
  },
];

/** Kind and line number only: the matched text is the secret. */
function scan(clean) {
  const found = [];
  const seen = new Set();
  clean.split(/\r?\n/).forEach((line, index) => {
    const lineNumber = index + 1;
    const report = (kind) => {
      const key = kind + ":" + lineNumber;
      if (seen.has(key)) return;
      seen.add(key);
      found.push({ kind, line: lineNumber });
    };
    for (const key of keys) {
      if (line.includes(key)) report("a map key survived sanitising");
    }
    for (const pattern of LEAK_PATTERNS) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(line)) !== null) {
        if (pattern.allow && pattern.allow(match[0], line, match.index)) continue;
        report(pattern.kind);
      }
    }
  });
  return found;
}

const clean = [];
for (const name of readdirSync(SKILLS)) {
  const source = join(SKILLS, name, "SKILL.md");
  try {
    if (!statSync(source).isFile()) continue;
  } catch {
    continue;
  }
  const sanitized = sanitize(readFileSync(source, "utf8"));
  clean.push({ name, sanitized, findings: scan(sanitized) });
}

const leaking = clean.filter((skill) => skill.findings.length > 0);
if (leaking.length > 0) {
  for (const skill of leaking) {
    console.error("LEAK " + skill.name + ".md is not publishable:");
    for (const finding of skill.findings) {
      console.error("  line " + finding.line + ": " + finding.kind);
    }
  }
  console.error(
    "Nothing was written. Add the missing value to .dsh/publish-map.json, then run this again.",
  );
  process.exitCode = 1;
} else {
  mkdirSync(OUT, { recursive: true });
  for (const skill of clean) {
    writeFileSync(join(OUT, skill.name + ".md"), skill.sanitized);
    console.log(
      "  " + skill.name.padEnd(24) + skill.sanitized.length + " bytes  clean",
    );
  }
  console.log("wrote " + clean.length + " skill(s) to docs/skills/");
}
