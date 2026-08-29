// Sopotnik — proba Codex REALTIME glasu (eksperimentalno, prek ChatGPT naročnine).
// Zagon na Macu:  node realtime-probe.mjs
// Izhod: izpis diagnostike + sven-pogovor.wav in sven-tts.wav (poslušaj ju!)
// Glas izbereš z:  SOPOTNIK_VOICE=cedar node realtime-probe.mjs  (privzeto marin)

import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { writeFileSync } from "node:fs";

const VOICE = process.env.SOPOTNIK_VOICE || "marin";
const INSTRUCTIONS =
  "Ti si Sven, prijazen slovenski glasovni pomočnik v zasebni aplikaciji Sopotnik. " +
  "Vedno govoriš samo slovensko, kratko in naravno.";

const child = spawn("npx", ["codex", "app-server", "--enable", "realtime_conversation"], {
  stdio: ["pipe", "pipe", "pipe"],
});
child.stderr.on("data", (d) => process.stderr.write("[app-server] " + d));
const rl = createInterface({ input: child.stdout });

let nextId = 1;
const pending = new Map();

function request(method, params, timeoutMs = 30_000) {
  const id = nextId++;
  child.stdin.write(JSON.stringify({ id, method, params }) + "\n");
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method}: ni odgovora v ${timeoutMs / 1000} s`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, t, method });
  });
}

// zbiranje zvoka in prepisov
let audioChunks = [];
let sampleRate = 24000;
let numChannels = 1;
let lastAudioAt = 0;
const transcripts = [];
let realtimeStarted = null;
let realtimeError = null;

rl.on("line", (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }

  if (msg.id !== undefined && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    clearTimeout(p.t);
    if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message ?? JSON.stringify(msg.error)}`));
    else p.resolve(msg.result ?? {});
    return;
  }

  switch (msg.method) {
    case "thread/realtime/started":
      realtimeStarted = msg.params;
      console.log(`✅ realtime seja sprejeta (verzija ${msg.params?.version}, id ${msg.params?.realtimeSessionId ?? "-"})`);
      break;
    case "thread/realtime/outputAudio/delta": {
      const a = msg.params?.audio;
      if (a?.data) {
        audioChunks.push(Buffer.from(a.data, "base64"));
        sampleRate = a.sampleRate || sampleRate;
        numChannels = a.numChannels || numChannels;
        lastAudioAt = Date.now();
      }
      break;
    }
    case "thread/realtime/transcript/delta":
    case "thread/realtime/item/transcript/delta": {
      const t = msg.params?.delta ?? msg.params?.text ?? "";
      const role = msg.params?.role ?? "";
      if (t) transcripts.push(`${role}${role ? ": " : ""}${t}`);
      break;
    }
    case "thread/realtime/error":
      realtimeError = msg.params;
      console.error("✗ realtime napaka:", JSON.stringify(msg.params));
      break;
    case "thread/realtime/closed":
      console.log("ℹ realtime seja zaprta:", JSON.stringify(msg.params ?? {}));
      break;
  }
});

function wavFromPcm16(chunks, rate, channels) {
  const data = Buffer.concat(chunks);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * channels * 2, 28);
  header.writeUInt16LE(channels * 2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function collectAudio(label, file, maxWaitMs = 45_000) {
  const start = Date.now();
  // počakaj na prvi zvok, nato do 2,5 s tišine
  while (Date.now() - start < maxWaitMs) {
    await sleep(300);
    if (audioChunks.length > 0 && Date.now() - lastAudioAt > 2_500) break;
    if (realtimeError) break;
  }
  const bytes = audioChunks.reduce((n, b) => n + b.length, 0);
  const secs = bytes / (sampleRate * numChannels * 2);
  if (bytes > 0) {
    writeFileSync(file, wavFromPcm16(audioChunks, sampleRate, numChannels));
    console.log(`🔊 ${label}: ${secs.toFixed(1)} s zvoka (${sampleRate} Hz, ${numChannels} kanal) -> ${file}`);
  } else {
    console.log(`✗ ${label}: ni prejetega zvoka`);
  }
  audioChunks = [];
  return bytes > 0;
}

async function main() {
  console.log(`Sopotnik — proba Codex realtime glasu (glas: ${VOICE})`);

  const init = await request("initialize", {
    clientInfo: { name: "sopotnik-probe", version: "0.1" },
    capabilities: { experimentalApi: true },
  });
  console.log(`initialize OK (${init.userAgent ?? "?"})`);

  const ts = await request("thread/start", {});
  const threadId = ts.thread?.id ?? ts.threadId;
  if (!threadId) throw new Error("thread/start ni vrnil id niti");
  console.log(`thread ${threadId}`);

  try {
    const v = await request("thread/realtime/listVoices", {});
    console.log("glasovi:", JSON.stringify(v.voices ?? v));
  } catch (e) {
    console.log(`⚠ listVoices: ${e.message} (nadaljujem s start)`);
  }

  await request("thread/realtime/start", {
    threadId,
    outputModality: "audio",
    transport: { type: "websocket" },
    voice: VOICE,
    includeStartupContext: false,
    realtimeStartInstructions: INSTRUCTIONS,
  });
  console.log("thread/realtime/start sprejet — čakam potrditev seje …");

  const t0 = Date.now();
  while (!realtimeStarted && !realtimeError && Date.now() - t0 < 30_000) await sleep(200);
  if (!realtimeStarted) {
    throw new Error(realtimeError ? `seja ni stekla: ${JSON.stringify(realtimeError)}` : "seja ni stekla (timeout)");
  }

  console.log("\n— TEST A: pogovor (appendText) —");
  await request("thread/realtime/appendText", {
    threadId,
    text: "Pozdravi me in se v dveh stavkih predstavi.",
    role: "user",
  });
  const okA = await collectAudio("pogovor", "sven-pogovor.wav");

  console.log("\n— TEST B: branje besedila (appendSpeech = čisti TTS) —");
  await request("thread/realtime/appendSpeech", {
    threadId,
    text: "To je preizkus branja poljubnega besedila. Šumniki: čas, žoga, širina. Danes je sobota, devetindvajsetega avgusta.",
  });
  const okB = await collectAudio("TTS", "sven-tts.wav");

  if (transcripts.length) {
    console.log("\nprepisi:", transcripts.join("").slice(0, 500));
  }

  await request("thread/realtime/stop", { threadId }).catch(() => {});
  console.log(`\n━━ povzetek ━━\npogovor: ${okA ? "✅" : "✗"}  |  čisti TTS: ${okB ? "✅" : "✗"}`);
  console.log("Poslušaj sven-pogovor.wav in sven-tts.wav (dvoklik v Finderju) ter izpis prilepi v pogovor s Claudom.");
  child.kill();
  process.exit(okA || okB ? 0 : 1);
}

main().catch((e) => {
  console.error("\n✗ Proba ni uspela:", e.message);
  console.error("Če piše 'method not found' ali omenja onemogočeno funkcijo, je realtime za ta račun/verzijo še zaklenjen.");
  child.kill();
  process.exit(1);
});
