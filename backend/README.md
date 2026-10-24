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

Nastavljivo prek okolja (ali v `backend/.env`; komentar v vrstici `KLJUČ=vrednost  # opomba` je dovoljen):

| Spremenljivka | Privzeto | Pomen |
|---|---|---|
| `PORT` | 8787 | vrata |
| `SOPOTNIK_HOST` | vsi vmesniki | npr. Tailscale IP (`100.x.y.z`), da gateway ne posluša na domačem omrežju |
| `SOPOTNIK_MODEL` / `SOPOTNIK_EFFORT` | `gpt-5.6-luna` / `low` | klasični način |
| `SOPOTNIK_BRAIN_EFFORT` | `medium` | pametni možgani (ask_brain) |
| `SOPOTNIK_BRAIN_SANDBOX` | (vklopljen) | `off` izklopi omejitev Codexa za ask_brain (le za diagnostiko) |
| `SOPOTNIK_FORENSIC` | (izklopljen) | `1` = v `gateway.log` tudi vsebina argumentov in izidov orodij (privzeto le ime in vrsta izida) |

**Pametni možgani** tečejo v ločenem Codexu: brez okolja gatewaya (ključi iz `.env`), v prazni
mapi `$TMPDIR/sopotnik-brain` in z zavrnjenim branjem domače mape (razen `~/.codex`). Če bi
Codex s tem na kateri različici ne tekel, se v dnevniku pokaže napaka možganov — takrat
začasno `SOPOTNIK_BRAIN_SANDBOX=off` in javi.

**Testi brez telefona:** `node test-lock.mjs` (skladnost pravil zaklepa z aplikacijo) in
`node test-gateway.mjs` (celoten gateway proti lažnemu Realtime strežniku). Oba tečeta tudi v CI.

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
2 minutah brez pogovora samodejno konča (ob zaklenjenem telefonu najpozneje po 4 minutah,
sicer po 30). Klici tudi v živo zahtevajo ustno potrditev,
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
| ← | `{t:"ready", proto, gw}` | pripravljen; `proto: 2` od gatewaya 0.4.0 (aplikacija 0.4.0 ob starejšem gatewayu opozori, da potrditve ne bodo delovale) |
| → | `{t:"user_turn", text}` | uporabnikov izrek |
| ← | `{t:"say_delta", text}` | del govorjenega odgovora (za sproten TTS) |
| ← | `{t:"turn_done", say, actions[]}` | konec obrata; `actions` v fazi 1 vedno prazen |
| → | `{t:"reset"}` | nov pogovor (svež Codex thread) |
| ← | `{t:"error", message}` | napaka (slovensko, primerno za TTS) |

### Sven Live (rt_*)

| Smer | Sporočilo | Pomen |
|---|---|---|
| → | `{t:"hello", token, proto}` | `proto: 2` (od APK 0.4.0) = aplikacija pozna zaklep |
| → | `{t:"rt_start", rate, locked}` | odpri Realtime sejo; `locked` = telefon zaklenjen. Pri `proto ≥ 2` brez polja = zaklenjeno (fail-closed); stara aplikacija (proto 1) = odklenjeno in prejšnja navodila modelu (send_reply v enem koraku) — dosedanje vedenje |
| → | `{t:"rt_lock", locked}` | zaklep se je spremenil → gateway odpre NOVO sejo (kontekst pozabljen) in vrne `rt_reset` |
| ← | `{t:"rt_reset", locked}` | nova seja po spremembi zaklepa; telefon pozabi pripravljena dejanja |
| → | `{t:"rt_audio", data}` / `{t:"rt_stop"}` / `{t:"rt_cancel"}` | zvok (PCM16 base64) / konec / prekini Svenov odgovor |
| ← | `{t:"rt_ready"}`, `{t:"rt_audio"}`, `{t:"rt_cut", item}` | seja pripravljena / Svenov zvok / uporabnik je začel govoriti (`item` = id izjave) |
| ← | `{t:"rt_user_text", text, item}` / `{t:"rt_sven_text", text, resp}` | prepis uporabnikove izjave `item` (tudi prazen) / Svenovega odgovora `resp` |
| ← | `{t:"rt_resp_done", resp}` | Svenov odgovor `resp` je končan |
| → | `{t:"rt_heard", resp}` | telefon: odgovor `resp` je bil predvajan do konca brez prekinitve — šele tak šteje kot »slišan« (pogoj za potrditve) |
| ← | `{t:"rt_action", callId, name, args}` → `{t:"rt_action_result", callId, output}` | orodje na telefonu in njegov izid (izid za neznan `callId` se zavrže) |
| ← | `{t:"rt_audit", type, detail, tier, outcome}` | zapis strežniškega dejanja (npr. shrani_zapisek) v dnevnik dejanj na telefonu |
| ← | `{t:"rt_error", message}` / `{t:"rt_closed"}` | trajna napaka / seja zaprta (nefatalne napake API se le zabeležijo) |

Ob zaklenjenem telefonu gateway dovoli le orodja iz `OK_WHEN_LOCKED` (realtime.mjs); ostala
zavrne z izidom `ZAKLENJENO` in jih telefonu sploh ne pošlje. Telefon ima isto (podrobnejšo)
tabelo v `LockPolicy.kt` — `test-lock.mjs` preverja, da se ujemata.

Model v odgovoru lahko (v prihodnjih fazah) doda vrstico `⟦AKCIJE⟧` + JSON seznam dejanj;
strežnik jo izreže iz govora in pošlje v `turn_done.actions`.
