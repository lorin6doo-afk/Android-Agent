// Sopotnik — dostop do Obsidian vaulta (branje, iskanje, shranjevanje zapiskov).
// Pot: SOPOTNIK_VAULT ali prvi odprti vault iz Obsidianove konfiguracije.
// Vse teče na gatewaju (Mac mini); telefon ni vpleten.

import {
  readFileSync, readdirSync, statSync, writeFileSync, appendFileSync, existsSync, mkdirSync,
} from "node:fs";
import { join, basename, relative } from "node:path";
import { homedir } from "node:os";

function resolveVault() {
  if (process.env.SOPOTNIK_VAULT) return process.env.SOPOTNIK_VAULT;
  try {
    const cfg = join(homedir(), "Library/Application Support/obsidian/obsidian.json");
    const j = JSON.parse(readFileSync(cfg, "utf8"));
    const vaults = Object.values(j.vaults || {});
    const open = vaults.find((v) => v.open) || vaults[0];
    if (open?.path) return open.path;
  } catch { /* ni Obsidiana */ }
  return null;
}

export const VAULT_DIR = resolveVault();
const SVEN_SUBDIR = "Sopotnik"; // nove zapiske shranjujemo sem
const EXCLUDE = new Set([".obsidian", ".trash", ".git", "node_modules"]);

const norm = (s) =>
  String(s).normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();

function walk(dir, out, depth = 0) {
  if (depth > 8) return;
  for (const name of readdirSync(dir)) {
    if (EXCLUDE.has(name) || name.startsWith(".")) continue;
    const p = join(dir, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) walk(p, out, depth + 1);
    else if (name.toLowerCase().endsWith(".md")) out.push(p);
  }
}

function listNotes() {
  if (!VAULT_DIR) { const e = new Error("vault ni nastavljen"); e.code = "NOVAULT"; throw e; }
  const out = [];
  walk(VAULT_DIR, out); // vrže EPERM, če node nima dostopa do mape
  return out;
}

export function vaultStatus() {
  if (!VAULT_DIR) return { dir: null, ok: false, error: "vault ni nastavljen" };
  try {
    const out = [];
    walk(VAULT_DIR, out);
    return { dir: VAULT_DIR, ok: true, count: out.length };
  } catch (e) {
    return { dir: VAULT_DIR, ok: false, error: e.code === "EPERM" ? "ni dostopa (EPERM) — dodaj node v Popoln dostop do diska" : e.message };
  }
}

export function searchNotes(query, limit = 6) {
  const q = norm(query);
  if (!q) return [];
  const qtok = q.split(/\s+/).filter(Boolean);
  const scored = [];
  for (const p of listNotes()) {
    const title = basename(p, ".md");
    const nt = norm(title);
    let content = "";
    try { content = readFileSync(p, "utf8"); } catch { continue; }
    const nc = norm(content);
    let score = 0;
    if (nt === q) score += 100;
    else if (nt.includes(q)) score += 60;
    for (const t of qtok) { if (nt.includes(t)) score += 15; if (nc.includes(t)) score += 5; }
    if (score > 0) {
      const idx = nc.indexOf(qtok[0] ?? q);
      const snip = (idx >= 0 ? content.slice(Math.max(0, idx - 40), idx + 140) : content.slice(0, 140))
        .replace(/\s+/g, " ").trim();
      scored.push({ title, path: relative(VAULT_DIR, p), snippet: snip, score });
    }
  }
  scored.sort((a, b) => b.score - a.score || a.title.length - b.title.length);
  return scored.slice(0, limit);
}

export function readNote(title, maxChars = 4000) {
  const q = norm(title);
  if (!q) return null;
  let best = null, bestScore = 0;
  for (const p of listNotes()) {
    const nt = norm(basename(p, ".md"));
    const sc = nt === q ? 100 : nt.startsWith(q) ? 70 : nt.includes(q) ? 50 : 0;
    if (sc > bestScore) { bestScore = sc; best = p; }
  }
  if (!best) return null;
  let c = readFileSync(best, "utf8");
  if (c.length > maxChars) {
    // shrani_zapisek dodaja NA KONEC — ohrani začetek in najnovejši del, izpusti sredino
    const head = Math.floor(maxChars * 0.3);
    c = c.slice(0, head) + "\n… (sredina zapiska izpuščena) …\n" + c.slice(c.length - (maxChars - head));
  }
  return { title: basename(best, ".md"), content: c };
}

/** Obstoječ zapisek z NATANKO tem naslovom kjerkoli v vaultu (ali null). */
function findExact(title) {
  const q = norm(title);
  if (!q) return null;
  for (const p of listNotes()) if (norm(basename(p, ".md")) === q) return p;
  return null;
}

export function saveNote(title, text) {
  if (!VAULT_DIR) { const e = new Error("vault ni nastavljen"); e.code = "NOVAULT"; throw e; }
  const safe = (String(title || "Zapisek").replace(/[/\\:*?"<>|]/g, "-").replace(/\s+/g, " ").trim().slice(0, 80)) || "Zapisek";
  const dir = join(VAULT_DIR, SVEN_SUBDIR);
  // »dodaj v zapisek X«: če X že obstaja kjerkoli v vaultu, dopiši tja (sicer bi nastal dvojnik)
  const p = findExact(safe) ?? join(dir, safe + ".md");
  if (!existsSync(dir) && !existsSync(p)) mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().slice(0, 16).replace("T", " ");
  const body = String(text ?? "").trim();
  if (existsSync(p)) {
    appendFileSync(p, `\n\n---\n_${stamp}_\n${body}\n`);
    return { title: basename(p, ".md"), path: relative(VAULT_DIR, p), mode: "dodano" };
  }
  writeFileSync(p, `# ${safe}\n\n_${stamp}_\n${body}\n`);
  return { title: safe, path: relative(VAULT_DIR, p), mode: "ustvarjeno" };
}
