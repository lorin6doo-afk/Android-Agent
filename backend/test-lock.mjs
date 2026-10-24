// Preverba skladnosti zaklepa: vsako orodje Sven Live mora imeti NAMERNO pravilo v
// LockPolicy.kt, gateway pa mora dovoliti ob zaklepu natanko ALLOW + pogojna orodja.
// Zagon: node backend/test-lock.mjs   (izhod 0 = OK)
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { TOOLS, SERVER_TOOLS, OK_WHEN_LOCKED } from "./realtime.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const kt = readFileSync(join(here, "../android/app/src/main/java/si/sopotnik/LockPolicy.kt"), "utf8");
const sets = {};
for (const m of kt.matchAll(/val (\w+) = setOf\(([\s\S]*?)\)/g)) {
  sets[m[1]] = new Set([...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]));
}
const need = ["ALLOW_TOOLS", "FORBID_TOOLS", "CONDITIONAL_TOOLS", "UNLOCK_TOOLS", "SERVER_TOOLS"];
let fail = 0;
const bad = (msg) => { console.error("NAPAKA:", msg); fail++; };
for (const n of need) if (!sets[n]) bad(`LockPolicy.kt nima ${n}`);
if (fail) process.exit(1);

const names = TOOLS.map((t) => t.name);
const groups = ["ALLOW_TOOLS", "FORBID_TOOLS", "CONDITIONAL_TOOLS", "UNLOCK_TOOLS"];
for (const n of names) {
  const inG = groups.filter((g) => sets[g].has(n));
  if (inG.length !== 1) bad(`orodje ${n}: pravil ${inG.length} (${inG.join(", ") || "nobeno"}) — potrebno natanko eno`);
}
for (const g of groups) for (const n of sets[g]) if (!names.includes(n)) bad(`${g} vsebuje neznano orodje ${n}`);

const expectOk = new Set([...sets.ALLOW_TOOLS, ...sets.CONDITIONAL_TOOLS]);
for (const n of expectOk) if (!OK_WHEN_LOCKED.has(n)) bad(`gateway OK_WHEN_LOCKED nima ${n}`);
for (const n of OK_WHEN_LOCKED) if (!expectOk.has(n)) bad(`gateway OK_WHEN_LOCKED ima odvečno ${n}`);

for (const n of SERVER_TOOLS) if (!sets.SERVER_TOOLS.has(n)) bad(`SERVER_TOOLS: ${n} manjka v LockPolicy.kt`);
for (const n of sets.SERVER_TOOLS) if (!SERVER_TOOLS.has(n)) bad(`SERVER_TOOLS: ${n} manjka v realtime.mjs`);
for (const n of SERVER_TOOLS) if (sets.FORBID_TOOLS.has(n) || sets.CONDITIONAL_TOOLS.has(n)) bad(`strežniško orodje ${n} ne sme biti FORBID/pogojno`);

if (fail) { console.error(`test-lock: ${fail} napak`); process.exit(1); }
console.log(`test-lock: OK (${names.length} orodij, ob zaklepu dovoljenih ${OK_WHEN_LOCKED.size})`);
