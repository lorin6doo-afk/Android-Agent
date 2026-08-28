// Sopotnik — faza 0: proba Codex povezave in meritev latence.
// Zagon:  node probe.mjs
// Model:  SOPOTNIK_MODEL=gpt-5.6-luna node probe.mjs   (prazno = privzeti model)

import { Codex } from "@openai/codex-sdk";

const MODEL = process.env.SOPOTNIK_MODEL ?? "gpt-5.6-luna";
const EFFORT = process.env.SOPOTNIK_EFFORT ?? "low";

const schema = {
  type: "object",
  properties: {
    say: {
      type: "string",
      description: "Kratek odgovor v slovenščini, največ dva stavka.",
    },
  },
  required: ["say"],
  additionalProperties: false,
};

const ms = (t) => `${Math.round(t)} ms`;

async function runTurn(thread, label, prompt) {
  const t0 = performance.now();
  let tFirstEvent = null;
  let tFirstMessage = null;
  let finalText = "";
  let usage = null;
  let failure = null;

  const { events } = await thread.runStreamed(prompt, { outputSchema: schema });
  for await (const ev of events) {
    if (tFirstEvent === null) tFirstEvent = performance.now();
    if (
      (ev.type === "item.started" || ev.type === "item.updated" || ev.type === "item.completed") &&
      ev.item?.type === "agent_message"
    ) {
      if (tFirstMessage === null) tFirstMessage = performance.now();
      finalText = ev.item.text;
    }
    if (ev.type === "turn.completed") usage = ev.usage;
    if (ev.type === "turn.failed") failure = ev.error?.message ?? "neznana napaka";
    if (ev.type === "error") failure = ev.message;
  }
  const t1 = performance.now();

  console.log(`\n━━ ${label} ━━`);
  if (failure) {
    console.log(`✗ obrat NEUSPEŠEN: ${failure}`);
    return { ok: false, failure };
  }
  let say = finalText;
  try {
    say = JSON.parse(finalText).say ?? finalText;
  } catch {
    /* model ni vrnil čistega JSON — pokaži surovo */
  }
  console.log(`odgovor: »${say}«`);
  console.log(`prvi dogodek: ${ms(tFirstEvent - t0)}  |  prvo besedilo: ${tFirstMessage ? ms(tFirstMessage - t0) : "—"}  |  skupaj: ${ms(t1 - t0)}`);
  if (usage) {
    console.log(`žetoni: vhod ${usage.input_tokens} (predpomnjenih ${usage.cached_input_tokens}), izhod ${usage.output_tokens}`);
  }
  return { ok: true, total: t1 - t0 };
}

async function main() {
  console.log("Sopotnik — faza 0: proba Codex povezave");
  console.log(`model: ${MODEL || "(privzeti)"}, reasoning: ${EFFORT}`);

  const codex = new Codex();
  const threadOptions = {
    sandboxMode: "read-only",
    skipGitRepoCheck: true,
    modelReasoningEffort: EFFORT,
    ...(MODEL ? { model: MODEL } : {}),
  };

  let thread = codex.startThread(threadOptions);

  const intro =
    "Ti si Sven, slovenski glasovni pomočnik aplikacije Sopotnik. " +
    "Vedno odgovarjaš kratko, naravno in v slovenščini, v JSON obliki po shemi. " +
    "Uporabnik pravi: »Živjo! Na kratko se predstavi in povej eno zanimivost o Sloveniji.«";

  let r1 = await runTurn(thread, "1. obrat (hladen start)", intro);

  if (!r1.ok && MODEL && /model/i.test(r1.failure ?? "")) {
    console.log(`\nModel »${MODEL}« očitno ni na voljo — poskušam s privzetim modelom …`);
    thread = codex.startThread({ sandboxMode: "read-only", skipGitRepoCheck: true });
    r1 = await runTurn(thread, "1. obrat (privzeti model)", intro);
  }
  if (!r1.ok) {
    console.log("\nNamigi: ali si prijavljen? Poženi:  npx codex login --device-auth");
    process.exit(1);
  }

  const r2 = await runTurn(
    thread,
    "2. obrat (topla nit — test konteksta)",
    "Uporabnik pravi: »V enem stavku ponovi, katero zanimivost si mi pravkar povedal.«"
  );

  console.log("\n━━ povzetek ━━");
  const grade = (t) => (t < 2000 ? "odlično" : t < 5000 ? "v redu za pogovor" : t < 8000 ? "na meji" : "prepočasi");
  if (r1.ok) console.log(`hladen start: ${ms(r1.total)} (${grade(r1.total)})`);
  if (r2.ok) console.log(`topla nit:    ${ms(r2.total)} (${grade(r2.total)})`);
  console.log("Ta rezultat prilepi v pogovor s Claudom.");
}

main().catch((e) => {
  console.error("\n✗ Proba ni uspela:", e?.message ?? e);
  console.error("Namigi:");
  console.error(" • prijava:  npx codex login --device-auth");
  console.error(" • preveri:  npx codex --version");
  process.exit(1);
});
