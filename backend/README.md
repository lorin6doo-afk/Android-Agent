# Sopotnik — backend »gateway«

Most med telefonom in Codexom (tvoja ChatGPT Pro naročnina prek uradnega SDK).
Teče na Mac mini; telefon se poveže prek Tailscale (WebSocket, protokol v1).

## Namestitev (Mac mini)

```bash
cd Android-Agent/backend

# 1) odvisnosti (Codex SDK + CLI prineseta s sabo binarko)
npm install

# 2) prijava je na tem Macu že narejena; sicer:
#    npx codex login --device-auth

# 3) ustvari žeton in zaženi
export SOPOTNIK_TOKEN=$(openssl rand -hex 24)
echo "Žeton (vpiši ga v aplikacijo): $SOPOTNIK_TOKEN"
node server.mjs
```

Nastavljivo prek okolja: `PORT` (8787), `SOPOTNIK_MODEL` (`gpt-5.6-luna`), `SOPOTNIK_EFFORT` (`low`).

## Sven Live (realtime govor-v-govor) — zahteva OpenAI API ključ

1. Ustvari ključ na https://platform.openai.com/api-keys (in dodaj plačilno sredstvo v Billing).
2. Strežnik zaženi z obema ključema:
   ```bash
   export SOPOTNIK_TOKEN=<tvoj-obstoječi-žeton>
   export OPENAI_API_KEY=sk-...
   node server.mjs
   ```
3. V aplikaciji vklopi **Nastavitve → Sven Live** in začni pogovor.

Nastavljivo: `SOPOTNIK_RT_MODEL` (`gpt-realtime`; ceneje: `gpt-realtime-mini`) in
`SOPOTNIK_RT_VOICE` (`marin`; še: `cedar`, `alloy`, `sage`, `coral` …).
Okvirni strošek: polni model ≈ 0,05 $/min pogovora, mini ≈ 0,016 $/min; seja se ob
2 minutah tišine samodejno konča. Klici tudi v živo zahtevajo ustno potrditev,
kličejo pa izključno številko, ki jo je pred tem našel `find_contact` (trdno pravilo v aplikaciji).

## Tailscale

1. na Macu: `brew install tailscale && sudo tailscale up` (ali aplikacija iz App Store),
2. na telefonu: aplikacija Tailscale (Play Store), prijava z istim računom,
3. ime naprave vidiš s `tailscale status` — telefon potem uporabi `ws://<ime>:8787`.

Backend tako **ni izpostavljen internetu**; promet šifrira WireGuard. Zato je v1 protokol `ws://` (brez lastnega TLS) sprejemljiv — brez Tailscale ne izpostavljaj vrat!

## Nastavitev telefona

V aplikaciji Sopotnik → Nastavitve: naslov `ws://<tailscale-ime>:8787`, žeton iz koraka 3,
nato »Preizkusi povezavo«.

## Samodejni zagon ob prijavi (macOS, neobvezno)

```bash
# najpreprosteje: Terminal -> ohrani okno; ali pa launchd:
cat > ~/Library/LaunchAgents/si.sopotnik.gateway.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>si.sopotnik.gateway</string>
  <key>WorkingDirectory</key><string>/POT/DO/Android-Agent/backend</string>
  <key>ProgramArguments</key><array>
    <string>/usr/local/bin/node</string><string>server.mjs</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>SOPOTNIK_TOKEN</key><string>TVOJ-ZETON</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
EOF
launchctl load ~/Library/LaunchAgents/si.sopotnik.gateway.plist
```

(Preveri pot do `node` z `which node` in popravi `/POT/DO`.)

## Protokol v1 (WebSocket, JSON sporočila)

| Smer | Sporočilo | Pomen |
|---|---|---|
| → | `{t:"hello", token}` | avtentikacija (žeton primerjan časovno varno) |
| ← | `{t:"ready"}` | pripravljen |
| → | `{t:"user_turn", text}` | uporabnikov izrek |
| ← | `{t:"say_delta", text}` | del govorjenega odgovora (za sproten TTS) |
| ← | `{t:"turn_done", say, actions[]}` | konec obrata; `actions` v fazi 1 vedno prazen |
| → | `{t:"reset"}` | nov pogovor (svež Codex thread) |
| ← | `{t:"error", message}` | napaka (slovensko, primerno za TTS) |

Model v odgovoru lahko (v prihodnjih fazah) doda vrstico `⟦AKCIJE⟧` + JSON seznam dejanj;
strežnik jo izreže iz govora in pošlje v `turn_done.actions`.
