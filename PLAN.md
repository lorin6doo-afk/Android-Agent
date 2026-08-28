# Android Agent — načrt in študija izvedljivosti

> **Status: predlog za skupno razpravo** (v1, 28. 8. 2026). Nič od spodnjega še ni implementirano — ta dokument je podlaga, da se najprej uskladiva o arhitekturi, obsegu MVP in odprtih vprašanjih (razdelek 10).

---

## 1. Cilj in vizija

Zasebna Android aplikacija — **osebni AI pomočnik, voice-first**, uporaben tudi v avtu in (kolikor Android dovoljuje) ob zaklenjenem telefonu.

**Delitev odgovornosti:**

| Plast | Odgovornost |
|---|---|
| **AI (Codex/ChatGPT)** | razumevanje jezika, kontekst, odločanje *kaj* narediti, povzemanje, sestavljanje besedil in odgovorov |
| **Android aplikacija** | izvajanje dejanj (klici, WhatsApp/SMS, glasba, navigacija, obvestila, sistemske akcije), zajem glasu (STT), izgovorjava (TTS), varnostne potrditve |
| **Backend (»gateway«)** | most med telefonom in Codex runtimom; tam živi ChatGPT/Codex OAuth prijava |

**Načela:**

- preproste ukaze izvede telefon **lokalno** (takoj, brez omrežja); AI se uporabi za kompleksne zahteve, povzetke in generiranje besedila,
- **brez stalnega poslušanja v ozadju** — samo eksplicitni sprožilci (gumb, ploščica, Bluetooth v avtu, sistemski assistant trigger),
- AI **nikoli ne izvede dejanja sam** — vedno vrne strukturiran predlog dejanja, ki ga aplikacija deterministično klasificira (zelena/rumena/rdeča) in po potrebi potrdi z glasom,
- minimalna dovoljenja, brez shranjevanja ChatGPT gesla, šifrirana komunikacija, lokalni audit log.

---

## 2. Povzetek izvedljivosti

*(matrika — glej razdelka 3 in 4 za podrobnosti in vire)*

<!-- FEASIBILITY_MATRIX -->

---

## 3. AI integracija: Codex / ChatGPT OAuth

<!-- CODEX_SECTION -->

---

## 4. Izvedljivost Android funkcij (podrobno)

<!-- ANDROID_SECTION -->

---

## 5. Predlagana arhitektura

```
        ┌────────────────────────── TELEFON (Android app, Kotlin) ──────────────────────────┐
        │                                                                                   │
 glas ─▶│ STT (streaming) ─▶ IntentRouter ──── preprost ukaz ──▶ ActionExecutor ─▶ dejanje  │
        │                        │                                    ▲                     │
        │                        │ kompleksno / besedilo              │ strukturirana       │
        │                        ▼                                    │ dejanja             │
        │                   AgentClient (WebSocket, TLS/Tailscale)    │                     │
        │                        │                                SafetyGate                │
        │                        │                             (zelena/rumena/rdeča,       │
        │  TTS ◀── odgovor ──────┘                              glasovna potrditev)         │
        └────────────│──────────────────────────────────────────────────────────────────────┘
                     │
        ┌────────────▼──────────── BACKEND »gateway« (doma / mini PC) ──────────────────────┐
        │  WebSocket strežnik ─▶ session manager ─▶ Codex runtime (app-server / SDK)        │
        │  • en Codex thread na pogovor (večturnost)      • ChatGPT/Codex OAuth prijava     │
        │  • system prompt + JSON shema dejanj            • streaming odgovorov             │
        └───────────────────────────────────────────────────────────────────────────────────┘
```

### 5.1 Android aplikacija — moduli

| Modul | Naloga |
|---|---|
| `VoiceSessionService` | foreground service (tip `microphone`); upravlja sejo: poslušanje → orkestracija → odgovor; drži notifikacijo z live statusom |
| `SpeechIO` | STT (streaming, delni rezultati) in TTS; barge-in (prekinitev TTS, ko uporabnik spregovori) v kasnejši fazi |
| `IntentRouter` | lokalna slovenska gramatika za hitre ukaze (»pokliči X«, »predvajaj glasbo«, »navigiraj domov«, »preberi obvestila«); vse ostalo gre na AI |
| `AgentClient` | WebSocket na backend; pošlje transkript + minimalen kontekst (aktivna obvestila, stanje predvajanja …), sprejme `{say, actions[]}` s streamingom |
| `ActionExecutor` | podmoduli: klici, mediji, navigacija, obvestila, sporočila, sistem |
| `SafetyGate` | deterministična klasifikacija dejanj (tabela v razdelku 6) + glasovni potrditveni dialog; klasifikacije NE dela AI |
| `TriggerModule` | Quick Settings ploščica, widget/bližnjica, Bluetooth-v-avtu (CompanionDeviceManager), kasneje vloga sistemskega asistenta |
| `NotifCenter` | `NotificationListenerService`: branje, predpomnjenje zadnjih obvestil, iskanje reply-akcij |
| `AuditLog` | lokalna Room baza: čas, transkript, klasifikacija, izid; brez zvočnih posnetkov; izvoz samo ročno |
| `Settings/Onboarding` | vodeno podeljevanje dovoljenj, izbire (potrditvene stopnje, whitelist kontaktov, naslov »domov« …) |

### 5.2 Backend »gateway«

- majhen **TypeScript/Node** (ali Python) proces; teče 24/7 na napravi doma (mini PC, Raspberry Pi 5, stari laptop) ali na malem VPS,
- **edino mesto s ChatGPT/Codex poverilnicami** (OAuth prijava se naredi enkrat, tokene upravlja Codex sam),
- na telefon izpostavljen **izključno prek Tailscale** (WireGuard VPN, brez odprtih portov proti internetu) — priporočena rešitev; alternativa je javni HTTPS z močnim tokenom,
- protokol do appa: en WebSocket, sporočila `user_turn`, `say_delta` (za sproten TTS), `actions`, `session_state`,
- AI vrača **strukturiran JSON po shemi** (`say`, `actions[]`, `needs_confirmation`), backend jo validira; neveljaven output se zavrne in ponovi,
- vsebina obvestil se modelu vedno označi kot **nezaupanja vreden podatek** (obramba pred prompt injection prek prejetih sporočil — model sporočil ne sme obravnavati kot ukaze).

### 5.3 Lokalno proti AI

| Gre lokalno (brez omrežja, <1,5 s) | Gre na AI |
|---|---|
| klic kontakta, predvajaj/pavza/naprej, glasnost, navigacija na znan cilj, svetilka, »preberi zadnja obvestila« (dobesedno) | povzemanje obvestil, sestavljanje odgovorov na sporočila, večturni pogovor, vprašanja, vse dvoumno ali česar lokalna gramatika ne prepozna |

Lokalna pot je hkrati **fallback**, ko backend ni dosegljiv (v avtu brez signala): osnovne funkcije morajo delovati vedno.

---

## 6. Varnostni sloj: zelena / rumena / rdeča

Klasifikacija je **deterministična tabela v Android kodi** (ne odločitev modela). AI lahko stopnjo samo *zviša* (predlaga potrditev), nikoli zniža.

| Stopnja | Pravilo | Primeri |
|---|---|---|
| 🟢 **zelena** — izvede takoj | dejanja brez učinka navzven ali povratna v sekundi | branje/povzetek obvestil, predvajanje/pavza/skip, glasnost, svetilka, prikaz/zagon navigacije, odgovori na vprašanja |
| 🟡 **rumena** — glasovna potrditev (»Pošljem?« → »da«) | dejanja, ki komunicirajo navzven v mojem imenu, na znane prejemnike | pošiljanje WhatsApp/SMS odgovora (AI prebere osnutek naglas), klic kontakta iz imenika (potrditev imena — ščiti pred napačnim STT ujemanjem), vklop/izklop »ne moti« |
| 🔴 **rdeča** — vedno eksplicitna potrditev, privzeto izklopljeno | dejanja s širšim dometom ali nepovratna | sporočilo v skupino ali več prejemnikom, klic/SMS na številko, ki ni v imeniku, brisanje česarkoli, sistemske spremembe; karkoli z denarjem je **izven obsega projekta** |

Dodatna pravila:

- potrditveni odgovor (»da«, »pošlji«, »prekliči«) se prepoznava **lokalno** s kratko gramatiko, ne prek AI,
- vsak izveden ukaz (in vsaka zavrnitev) gre v audit log,
- rumeno stopnjo za klice bo mogoče v nastavitvah spustiti na zeleno (za avto), rdečih pravil ne bo mogoče izklopiti z glasom — samo v nastavitvah na odklenjenem telefonu.

---

## 7. Zasebnost in varnost

- **Poverilnice:** ChatGPT/Codex OAuth živi samo na backendu (Codexova lastna shramba tokenov). Telefon ima le dolg naključen API token za backend, shranjen v Android Keystore / `EncryptedSharedPreferences`. ChatGPT gesla ne vidi in ne hrani nihče razen OpenAI-jeve prijavne strani.
- **Prenos:** vse app ↔ backend prek Tailscale (WireGuard) ali TLS; brez golega HTTP.
- **Podatki:** zvok se ne shranjuje nikjer; transkripti in vsebina obvestil se na backendu ne logirajo (le v RAM za sejo); audit log je samo na telefonu.
- **Dovoljenja:** zahtevajo se postopoma po fazah, vsako z razlago ob onboarding koraku:

| Faza | Dovoljenje / poseben dostop | Zakaj |
|---|---|---|
| 1 | `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE(_MICROPHONE)`, `POST_NOTIFICATIONS` | glasovna seja |
| 1 | `READ_CONTACTS`, `CALL_PHONE` | »pokliči X« |
| 2 | dostop do obvestil (`NotificationListenerService`, sistemska nastavitev) | branje/povzemanje + WhatsApp reply |
| 3 | `BLUETOOTH_CONNECT` + Companion Device dovoljenja | samodejni zagon v avtu |
| 5 (opcijsko) | `SEND_SMS`, `ACCESS_NOTIFICATION_POLICY`, `SYSTEM_ALERT_WINDOW` | SMS, »ne moti«, zanesljivejši zagon UI iz ozadja |

---

## 8. Fazni plan

**MVP = faze 0–2.** Vsaka faza je samostojno uporabna in se konča s preizkusom na pravem telefonu.

### Faza 0 — preverba temeljev (prototip, ~nekaj dni)
- mini vertikala: gumb → STT (sl-SI) → izpis → TTS nazaj,
- backend: Codex OAuth prijava + prvi programatski klic; meritev latence konec-govora → prvi-zvok,
- **go/no-go**: kakovost slovenskega STT/TTS, latenca AI poti, delovanje Codex poti.

### Faza 1 — jedro »glas → dejanje«
- foreground service + ploščica/widget za zagon seje,
- `IntentRouter` z lokalnimi ukazi: klic, glasba (play/pavza/naprej + »predvajaj X«), navigacija, glasnost,
- AI pot za vse ostalo (interpretacija + odgovor z TTS), streaming odgovora v TTS,
- `SafetyGate` + glasovne potrditve, audit log.

### Faza 2 — obvestila in sporočila (konec MVP)
- `NotificationListenerService`: »preberi obvestila« / »kaj je novega« s povzetkom prek AI,
- WhatsApp (in drugi messengerji) odgovor prek notification reply akcije: AI sestavi osnutek → prebere naglas → rumena potrditev → pošlji,
- obravnava roba: obvestilo medtem izginilo → pomočnik pove, da odgovor ni več mogoč.

### Faza 3 — avto
- CompanionDeviceManager asociacija z avtomobilskim Bluetoothom; samodejni zagon seje ob priklopu (pozdrav + pripravljenost),
- delovanje ob zaklenjenem zaslonu (FGS + zvok prek avta), zagon klica/navigacije iz ozadja,
- obnašanje brez omrežja: lokalni ukazi + jasno sporočilo, da AI ni dosegljiv.

### Faza 4 — večturni pogovor in sistemski asistent
- trajni pogovorni kontekst (follow-up brez ponovnega sprožilca, kratko okno poslušanja po odgovoru),
- registracija kot **privzeti asistent** (dolgi pritisk na power / kotna poteza) — tudi z zaklenjenega zaslona,
- barge-in (prekinitev TTS z govorom).

### Faza 5 — poliranje in opcije
- nastavitve (stopnje potrditev, whitelist kontaktov, »domov«/»služba«), izvoz audit loga,
- opcijsko: SMS pošiljanje, »ne moti«, alarmi/opomniki, dodatne sistemske akcije,
- vzdržljivostni testi baterije in zanesljivosti sprožilcev.

---

## 9. Znana tveganja

<!-- RISKS_SECTION -->

---

## 10. Odprta vprašanja za skupno odločitev

<!-- QUESTIONS_SECTION -->
