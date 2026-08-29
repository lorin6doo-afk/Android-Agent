// Sopotnik — backend »gateway« (protokol v1).
// Telefon <-> WebSocket <-> Codex (ChatGPT naročnina prek uradnega SDK).
//
// Zagon:  SOPOTNIK_TOKEN=<dolg-naključen-niz> node server.mjs
// Okolje: PORT (privzeto 8787), SOPOTNIK_MODEL (gpt-5.6-luna), SOPOTNIK_EFFORT (low)

import { WebSocketServer } from "ws";
import { Codex } from "@openai/codex-sdk";
import { timingSafeEqual } from "node:crypto";

const PORT = Number(process.env.PORT ?? 8787);
const TOKEN = process.env.SOPOTNIK_TOKEN ?? "";
const MODEL = process.env.SOPOTNIK_MODEL ?? "gpt-5.6-luna";
const EFFORT = process.env.SOPOTNIK_EFFORT ?? "low";

if (!TOKEN || TOKEN.length < 16) {
  console.error("Nastavi SOPOTNIK_TOKEN (vsaj 16 znakov), npr.:  SOPOTNIK_TOKEN=$(openssl rand -hex 24) node server.mjs");
  process.exit(1);
}

const MARKER = "⟦AKCIJE⟧";

const SYSTEM = [
  "Ti si Sven, slovenski glasovni pomočnik v zasebni aplikaciji Sopotnik.",
  "Tvoje odgovore telefon prebere naglas, zato odgovarjaj kratko (največ tri stavki), naravno in vedno v slovenščini.",
  "Brez markdowna, seznamov, emojijev ali posebnih znakov.",
  "Preprosta dejanja (klici, glasba, navigacija, glasnost, svetilka, odpiranje aplikacij) izvaja telefon sam. Če dobiš zahtevo po dejanju, pomeni, da je telefon ni prepoznal — takrat kratko in prijazno povej, da tega v tej fazi še ne znaš izvesti; nikoli ne svetuj, naj uporabnik ukaz ponovi ali izgovori drugače.",
  `Vrstica ${MARKER} je rezervirana za prihodnjo rabo; ne uporabljaj je.`,
].join(" ");

const codex = new Codex();

const safeEqual = (a, b) => {
  const A = Buffer.from(String(a));
  const B = Buffer.from(String(b));
  return A.length === B.length && timingSafeEqual(A, B);
};

const wss = new WebSocketServer({ port: PORT });

wss.on("connection", (ws, req) => {
  const peer = req.socket.remoteAddress;
  let authed = false;
  let thread = null;
  let firstTurn = true;
  let busy = false;

  const send = (o) => {
    try { ws.send(JSON.stringify(o)); } catch { /* povezava je morda že zaprta */ }
  };

  ws.on("message", async (data) => {
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }

    if (msg.t === "hello") {
      if (safeEqual(msg.token ?? "", TOKEN)) {
        authed = true;
        console.log(`[${new Date().toISOString()}] povezan ${peer}`);
        send({ t: "ready" });
      } else {
        send({ t: "error", message: "Napačen žeton." });
        ws.close(4001, "auth");
      }
      return;
    }

    if (!authed) { ws.close(4001, "auth"); return; }

    if (msg.t === "reset") { thread = null; firstTurn = true; return; }
    if (msg.t !== "user_turn" || typeof msg.text !== "string") return;

    if (busy) {
      send({ t: "error", message: "Sven še razmišlja o prejšnjem vprašanju." });
      return;
    }
    busy = true;
    const t0 = performance.now();

    try {
      if (!thread) {
        thread = codex.startThread({
          sandboxMode: "read-only",
          skipGitRepoCheck: true,
          approvalPolicy: "never",
          modelReasoningEffort: EFFORT,
          ...(MODEL ? { model: MODEL } : {}),
        });
        firstTurn = true;
      }

      const input = firstTurn ? `${SYSTEM}\n\nUporabnik: ${msg.text}` : msg.text;
      firstTurn = false;

      let full = "";
      let sentUpTo = 0;
      let usage = null;

      const { events } = await thread.runStreamed(input);
      for await (const ev of events) {
        if (
          (ev.type === "item.started" || ev.type === "item.updated" || ev.type === "item.completed") &&
          ev.item?.type === "agent_message"
        ) {
          full = ev.item.text ?? "";
          const mi = full.indexOf(MARKER);
          // Med streamanjem zadržimo rep, da markerja (ali njegovega začetka) ne preberemo naglas.
          const sendEnd = mi >= 0 ? mi : Math.max(sentUpTo, full.length - (MARKER.length + 2));
          if (sendEnd > sentUpTo) {
            send({ t: "say_delta", text: full.slice(sentUpTo, sendEnd) });
            sentUpTo = sendEnd;
          }
        }
        if (ev.type === "turn.completed") usage = ev.usage;
        if (ev.type === "turn.failed") throw new Error(ev.error?.message ?? "obrat ni uspel");
        if (ev.type === "error") throw new Error(ev.message);
      }

      const mi = full.indexOf(MARKER);
      const say = (mi >= 0 ? full.slice(0, mi) : full).trim();
      let actions = [];
      if (mi >= 0) {
        try {
          const parsed = JSON.parse(full.slice(mi + MARKER.length).trim());
          if (Array.isArray(parsed)) actions = parsed;
        } catch { /* neveljaven JSON -> obravnavamo kot govor brez dejanj */ }
      }

      const remEnd = mi >= 0 ? mi : full.length;
      if (remEnd > sentUpTo) send({ t: "say_delta", text: full.slice(sentUpTo, remEnd) });

      send({ t: "turn_done", say, actions });
      const dt = Math.round(performance.now() - t0);
      console.log(
        `[${new Date().toISOString()}] obrat ${dt} ms, žetoni: vhod ${usage?.input_tokens ?? "?"} ` +
        `(cache ${usage?.cached_input_tokens ?? "?"}), izhod ${usage?.output_tokens ?? "?"}`
      );
    } catch (e) {
      console.error(`[${new Date().toISOString()}] napaka obrata:`, e?.message ?? e);
      send({ t: "error", message: "Sven trenutno ni dosegljiv. Poskusi znova." });
      thread = null;
      firstTurn = true;
    } finally {
      busy = false;
    }
  });

  ws.on("close", () => console.log(`[${new Date().toISOString()}] odklop ${peer}`));
});

console.log(`Sopotnik gateway posluša na vratih ${PORT} (model: ${MODEL || "privzeti"}, reasoning: ${EFFORT})`);
console.log("Telefon nastavi na  ws://<tailscale-ime-naprave>:%d  z istim žetonom.", PORT);
