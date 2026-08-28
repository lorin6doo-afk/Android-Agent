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

**AI integracija (stanje avgust 2026):**

| Zmožnost | Ocena | Opomba |
|---|---|---|
| ChatGPT naročnina prek Codexa (OAuth, brez API ključa) | ✅ | uradno podprta pot; tudi headless prijava na strežniku |
| Programatski vmesnik (`codex app-server`, SDK-ji) | ✅ | uradna integracijska površina s streamingom |
| Lastna orodja za agenta (MCP strežniki) | ✅ | uradno podprto v Codex konfiguraciji |
| Ne-kodirna raba (osebni pomočnik) | 🟡 | sivo območje — ni prepovedano, ni pa namenska raba (gl. 3.3) |
| STT / TTS prek naročnine | ❌ | zvok ni vključen → sistemski Google STT/TTS (brezplačno) ali plačljiv API |

**Android funkcije (Android 14–16, sideload):**

| Funkcija | Ocena | Ključni pogoj / omejitev |
|---|---|---|
| Branje in povzemanje obvestil | ✅ | poseben dostop do obvestil; A15+ skrije OTP kode |
| Odgovor na WhatsApp/messenger | 🟡 🧪 | prek reply akcije *aktivnega* obvestila; krhko po aplikacijah |
| Klic kontakta | ✅ / 🟡 | iz ozadja le z asistentsko vlogo ali vidno sejo |
| Nadzor glasbe (play/pavza/naprej) | ✅ | prek MediaController (rabi dostop do obvestil) |
| »Predvajaj [izvajalca]« | 🟡 | search-intent nezanesljiv → fallback strategija |
| Navigacija (Google Maps) | ✅ / 🟡 | iz ozadja ista omejitev kot klici |
| SMS | ✅ | A15+: enkratni odklep »omejenih nastavitev« |
| Sistemske akcije | 🟡 | glasnost/svetilka/DND/alarmi ✅; WiFi/BT preklop ❌ |
| Mikrofon ob zaklenjenem zaslonu | 🟡 | **samo** kot privzeti asistent ali z dotikom obvestila |
| Samodejni zagon ob Bluetooth v avtu | 🟡 | zbudi aplikacijo + zvok ✅, mikrofona ne odpre |
| Vloga privzetega asistenta | ✅ | hrbtenica projekta (mikrofon, geste, zagon iz ozadja) |
| STT/TTS v slovenščini | 🟡 🧪 | Google omrežni STT + sistemski TTS solidna; offline negotov |
| Android Auto (zaslon v avtu) | ❌ | lasten asistentski UI ni dovoljen; zvok prek BT ✅ |
| Stalno poslušanje / wake-word | ❌ | sistemski API rezerviran; namerno opuščeno |

---

## 3. AI integracija: Codex / ChatGPT OAuth

### 3.1 Kaj je uradno podprto (preverjeno na openai/codex @ 28. 8. 2026)

- **Prijava z ChatGPT naročnino brez API ključa** — ✅ uradno priporočena pot: `codex login` (OAuth 2.0 + PKCE prek `auth.openai.com`). Žetoni se hranijo v `~/.codex/auth.json` (ali OS keyring) in se **samodejno osvežujejo** (~8-dnevni cikel); gesla nikoli ne vidimo. Codex je vključen v Plus/Pro/… plane ([docs](https://developers.openai.com/codex/auth)).
- **Prijava na strežniku brez brskalnika** — ✅ dokumentirane tri poti: `codex login --device-auth` (device-code, priporočeno), SSH tunel prijavnega porta (`ssh -L 1455:localhost:1455`), ali kopiranje `auth.json` z namizja.
- **[`codex app-server`](https://developers.openai.com/codex/app-server)** — ✅ uradna integracijska površina (z njo je zgrajen Codexov VS Code vtičnik): JSON-RPC 2.0 prek stdio, primitivi *thread / turn / item*, **streaming delta dogodki** (ključno za sproten TTS), prekinitve, upravljanje računa in **branje limitov** (`account/rateLimits/read`).
- **SDK-ja** — ✅ TypeScript `@openai/codex-sdk` in Python `openai-codex` (oba uradna, oba znata uporabiti obstoječo ChatGPT prijavo); `codex exec --json` za enostavne enkratne klice.
- **Lastna orodja** — ✅ Codex uradno podpira **lastne MCP strežnike** (`[mcp_servers]` v `~/.codex/config.toml`): agentu lahko izpostavimo orodja tipa `get_notifications`, `send_reply` ….
- **Izbira modela na zahtevo** — ✅ per-turn izbira modela in `model_reasoning_effort`. Trenutna paleta: GPT-5.6 v treh tierjih (*luna* = hiter/varčen, *terra*, *sol*). Za glasovni UX: **luna + nizek reasoning** za povzetke/osnutke, višji tier le za kompleksne naloge.
- **Limiti** — 🟡 poraba je token-metrirana v **skupnem bazenu** z ostalo Codex/ChatGPT-Work rabo: drseče 5-urno okno + tedenski limit (od 25. 8. 2026 5-urni limit spet velja za Plus; Pro je trenutno izvzet). Programsko berljivo, torej lahko pomočnik sam pove »limit se bliža«.

### 3.2 Predlagana vezava

Backend objame `codex app-server` (dolgoživ proces, en *thread* na pogovor → večturnost dobimo zastonj). Za vezavo dejanj **dva vzorca**:

1. **Strukturiran izhod (predlog za MVP):** model v enem obratu vrne JSON `{say, actions[]}` po naši shemi; backend validira, telefon izvede. En krog = najnižja latenca, popoln nadzor.
2. **MCP orodja (nadgradnja):** naša dejanja registrirana kot MCP orodja; Codexova agentska zanka jih med obratom sama kliče (npr. »preveri, ali mi je Ana odgovorila, in ji predlagaj termin« = več korakov). Elegantnejše za sestavljene naloge, a več krogov = več latence.

Začnemo z (1), (2) dodamo, ko se pokaže potreba.

### 3.3 Meje in česa se držimo

- **Ne bomo** pridobivali sejnih žetonov chatgpt.com ali kakorkoli obvozili uradnih poti — pogoji uporabe izrecno prepovedujejo programatsko ekstrakcijo in obhod zaščit; OpenAI je avgusta 2026 baniral račune, ki so naročnino prek proxyjev preprodajali kot API.
- **Sivo območje:** Codex je uradno pozicioniran »za razvoj programske opreme«; prepovedi ne-kodirne rabe nismo nikjer našli, tretji odjemalci na ChatGPT prijavi pa so s strani OpenAI javno tolerirani (≈10 % Codex prometa teče prek tujih harnessov). Naša raba: strogo **osebna, en uporabnik, zmerna količina** — najbolj benigna oblika sivega območja. Kljub temu:
- **Provider abstrakcija:** backend definira ozek vmesnik `AiProvider`; menjava na klasičen OpenAI API ključ (ali drug model) je konfiguracijska sprememba, ne prepis. To je naša zavarovalnica ob morebitni spremembi pogojev/limitov.
- **Spremljati:** *ChatGPT Work* (agent za splošne naloge, deli isti bazen porabe) zaenkrat nima javnega API-ja — če ga dobi, je produktno boljši fit od Codexa in preselitev bo enostavna zaradi abstrakcije.

### 3.4 Zvok (STT/TTS) — naročnina ga ne pokriva

ChatGPT/Codex žetoni ne dajejo dostopa do `/v1/audio/*` API-jev. Strategija:

- **MVP (0 €):** Androidov Google `SpeechRecognizer` (sl-SI, streaming) + sistemski Google TTS,
- **nadgradnja (nekaj € / mesec):** ločen OpenAI API ključ za `gpt-4o-mini-transcribe` (~0,003 $/min) in mini-TTS — opazno boljša slovenščina; smiselno šele, če faza 0 pokaže, da Google STT ne zadošča,
- Codexov eksperimentalni *realtime voice* način obstaja, a je interna funkcija TUI, ne API — samo spremljamo.

---

## 4. Izvedljivost Android funkcij (podrobno)

Ocene: ✅ podprto · 🟡 delno podprto / s pogoji · ❌ ni možno · 🧪 potreben prototip na ciljni napravi.
Cilj: Android 14–16, zasebna (sideload) namestitev — Google Play politike nas ne zavezujejo, omejitve OS pa.

### 4.1 Branje obvestil — ✅

[`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService) s posebnim dostopom »Dostop do obvestil« (uporabnik ga ročno vklopi v nastavitvah). Vidimo vsa obvestila, vključno z WhatsAppom (naslov, besedilo, akcije). **Omejitev od Androida 15:** obvestila, ki jih OS prepozna kot občutljiva (enkratne kode / 2FA), so za ne-sistemske poslušalce **redigirana** — pomočnik OTP kod ne bo videl, kar je za nas celo zaželeno. Običajna sporočila niso prizadeta.

### 4.2 Odgovarjanje na sporočila (WhatsApp, Telegram, Signal …) — 🟡 🧪

Deluje prek sprožitve **reply akcije obstoječega obvestila** (`RemoteInput` — isti kanal, kot ga uporabljajo pametne ure, Android Auto in Tasker): iz obvestila poiščemo akcijo z `RemoteInput`, vstavimo besedilo in sprožimo njen `PendingIntent`. Pogoji in tveganja:

- obvestilo mora biti **še aktivno** (ne odstranjeno, ne že odgovorjeno) — odgovarjati je torej mogoče na *prejeta* sporočila, ne začeti poljubnega pogovora,
- ni dokumentirana pogodba — struktura obvestil ciljne aplikacije se lahko spremeni z njeno posodobitvijo; WhatsApp občasno menja obnašanje → **prototip in test za vsako ciljno aplikacijo**, s smiselnim sporočilom ob neuspehu,
- uradnega API-ja za osebni WhatsApp ni (Business API za ta namen ni primeren) — ta tehnika je realno najboljša možna pot.

### 4.3 SMS — ✅ (z enkratnim odklepom)

`SmsManager` + `SEND_SMS` deluje brez statusa privzete SMS aplikacije. **Od Androida 15** je `SEND_SMS` za sideload namestitve »restricted setting« — enkratno ročno: *Podatki o aplikaciji → ⋮ → Dovoli omejene nastavitve* (ali namestitev prek adb). OS omejuje pošiljanje na ~30 SMS/30 min brez interakcije. Predlagam: SMS šele v fazi 5 (WhatsApp pokrije večino potreb).

### 4.4 Klici — ✅ v ospredju / 🟡 iz ozadja

`ACTION_CALL` + `CALL_PHONE` pokliče takoj; iskanje kontakta prek `ContactsContract` (`READ_CONTACTS`) z mehkim ujemanjem imena (STT!). Ker klic zažene aktivnost, iz **ozadja** velja [omejitev background activity launch](https://developer.android.com/guide/components/activities/background-starts) (Android 10+, zaostrena v 14/15): potrebna je izjema — vidno okno, dotik obvestila, `SYSTEM_ALERT_WINDOW` ali **status privzetega asistenta** (→ 4.10). V praksi: dokler je glasovna seja vidna (naša aktivnost/sejno okno), klici delujejo brez trikov.

### 4.5 Glasba — ✅ nadzor / 🟡 »predvajaj X«

- **Nadzor predvajanja (play/pavza/naprej/nazaj):** ✅ prek [`MediaSessionManager.getActiveSessions`](https://developer.android.com/reference/android/media/session/MediaSessionManager) + `MediaController` — deluje za Spotify, YouTube Music itd. Pogoj: podeljen dostop do obvestil (isti kot 4.1).
- **Zagon predvajanja z iskanjem (»predvajaj Siddharto«):** 🟡 `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` — Spotify ga honorira nezanesljivo (posebej ob ugasnjenem zaslonu včasih izvede iskanje brez predvajanja). Načrt: poskusi search-intent → če v ~2 s ni aktivne seje, fallback: zaženi aplikacijo + `play()` prek MediaController; uporabniku povemo, kaj se je zgodilo.
- **Media-key injekcija** (`dispatchMediaKeyEvent`): rezervni mehanizem, ne cilja določene aplikacije.

### 4.6 Navigacija — ✅ v ospredju / 🟡 iz ozadja

`google.navigation:q=<cilj>&mode=d` zažene Google Maps turn-by-turn brez posebnih dovoljenj. Iz ozadja ista BAL omejitev kot pri klicih (ista rešitev: vidna seja ali asistentski privilegij). Cilji »domov«/»služba« kot shranjena naslova v nastavitvah.

### 4.7 Sistemske akcije — 🟡

| Akcija | Ocena | Kako |
|---|---|---|
| glasnost | ✅ | `AudioManager` |
| svetilka | ✅ | `CameraManager.setTorchMode` (brez dovoljenja) |
| ne moti (DND) | ✅ | `setInterruptionFilter` + poseben dostop »Do Not Disturb access« |
| alarm / odštevalnik | ✅ | `AlarmClock.ACTION_SET_ALARM/TIMER` (izvede sistemska Ura) |
| WiFi / Bluetooth vklop-izklop | ❌ | programsko blokirano od Androida 10 (WiFi) / 13 (BT); fallback: odpremo [Settings Panel](https://developer.android.com/reference/android/provider/Settings.Panel), uporabnik tapne sam |

### 4.8 Mikrofon ob zaklenjenem zaslonu / iz ozadja — 🟡 (ključna arhitekturna omejitev)

Od Androida 14 veljata **dve naloženi omejitvi**: (1) foreground service tipa `microphone` se iz ozadja sploh ne sme zagnati brez izjeme; (2) tudi če se zažene, [mikrofona ne dobi](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), razen če je bil zagon sprožen z ene od **taksativno naštetih izjem** — med njimi: interakcija z obvestilom, interakcija z widgetom, sistemska komponenta, in **aplikacija, ki je nosilka `VoiceInteractionService`** (privzeti asistent).

**Na seznamu NISO:** CompanionDeviceManager dogodki, Bluetooth broadcasti, alarmi, `SYSTEM_ALERT_WINDOW`, zagon ob boot-u. Posledica: *nobena* avtomatika »avto se poveže → mikrofon se odpre« ni legalno mogoča. Mikrofon ob zaklenjenem telefonu dobimo samo kot **privzeti asistent** (4.10) ali z **dotikom obvestila** (en dotik, brez odklepa).

### 4.9 Sprožilci v avtu — 🟡 (zbudijo aplikacijo, ne odprejo pa mikrofona)

- **CompanionDeviceManager (priporočeno):** asociacija z avtomobilskim **klasičnim** Bluetoothom je podprta ([`AssociationRequest` + `BluetoothDeviceFilter`](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)); `CompanionDeviceService.onDeviceAppeared` se sproži ob priklopu tudi, če je bila aplikacija ubita. Dovoljenji `REQUEST_COMPANION_RUN_IN_BACKGROUND` in `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` omogočita zagon FGS (tip `connectedDevice`) iz ozadja. **Predvajanje zvoka (TTS pozdrav) je dovoljeno; mikrofon ni** (4.8). 🧪 zanesljivost/latenca `onDeviceAppeared` s klasičnim BT na realnem avtu.
- **`ACTION_ACL_CONNECTED` broadcast:** še vedno izvzet iz omejitev implicitnih broadcastov (manifest receiver deluje; `BLUETOOTH_CONNECT`), a manj zanesljiv od CDM in z istimi mikrofonskimi omejitvami. Rezervna pot.
- **Realen UX v avtu:** ob priklopu se aplikacija zbudi, pripravi sejo in objavi trajno obvestilo; glasovno sejo nato odpre **gumb za asistenta na volanu / dolgi pritisk power** (gre nosilcu vloge asistenta, tudi z zaklenjenim zaslonom — 4.10) ali en dotik obvestila.

### 4.10 Vloga privzetega asistenta (`VoiceInteractionService`) — ✅ hrbtenica projekta

Uporabnik aplikacijo nastavi kot **privzetega digitalnega asistenta** (`RoleManager.ROLE_ASSISTANT`; kot to naredi npr. Perplexity Assistant). S tem dobimo:

- **mikrofon iz ozadja in čez zaklenjen zaslon** (edina robustna izjema iz 4.8),
- **background activity launch privilegij** → klici in navigacija iz ozadja (4.4, 4.6),
- **sistemske sprožilne geste**: dolgi pritisk na power / kotna poteza / **gumb na slušalkah oz. volanu** — delujejo tudi z zaklenjenega zaslona,
- sejno okno (`VoiceInteractionSession`), ki se prikaže čez keyguard.

Cena: največji posamični implementacijski zalogaj (service + session + session UI + recognition plumbing) in 🧪 OEM posebnosti (Samsung side-key preusmerja na Bixby ipd.). Zato je v faznem planu to samostojna faza **pred** avtomobilsko.

### 4.11 UI čez zaklenjen zaslon — ✅

`Activity.setShowWhenLocked(true)` + `setTurnScreenOn(true)` zanesljivo prikaže sejo čez keyguard. Full-screen-intent obvestila so od Androida 14 omejena (dovoljenje ni samodejno) — nanje ne računamo.

### 4.12 STT za slovenščino — 🟡 🧪

- **Google `SpeechRecognizer` (privzeta pot za MVP):** sl-SI podprt prek omrežnega prepoznavalnika, s streaming delnimi rezultati in nizko latenco; točnost za slovenščino solidna, ne vrhunska. Brezplačno, brez dodatne infrastrukture.
- **On-device prepoznavanje** (`createOnDeviceSpeechRecognizer`): slovenski offline paket ni zagotovljen na vseh napravah → 🧪 preveriti s `checkRecognitionSupport()` na ciljnem telefonu. Brez njega ob odsotnosti omrežja STT ne dela (lokalni ukazi v avtu brez signala!).
- **Nadgradnja za točnost:** Whisper-razred prek OpenAI API (plačljivo: `gpt-4o-mini-transcribe` ~0,003 $/min, `whisper-1` ~0,006 $/min) — najboljša slovenščina, a doda latenco in strošek izven naročnine. Vosk nima slovenskega modela; whisper.cpp na telefonu je prepočasen za prijetno uporabo.

### 4.13 TTS za slovenščino — 🟡 🧪

Sistemski Google TTS ima sl-SI glas na večini naprav, a prisotnost ni zagotovljena → 🧪 runtime preverba `isLanguageAvailable()` + po potrebi poziv za namestitev glasu. Kakovost je »robotska, a razumljiva«. Nadgradnja: oblačni nevronski TTS (Google Chirp 3 HD podpira sl-SI; ElevenLabs, Azure) — lepši glas, a strošek + latenca + odvisnost od omrežja. MVP: sistemski TTS.

### 4.14 Android Auto — ❌ zaslon avta / ✅ zvok prek BT

Na zaslon Android Auto lastnega asistentskega UI **ni mogoče** pripeljati (dovoljene so samo predloge odobrenih kategorij: media/messaging/navigacija, z Googlovim pregledom). A aplikacija medtem **normalno teče na telefonu**: TTS in mikrofonska seja (prek asistentske poti) tečeta prek avtomobilskega Bluetooth zvoka. Za »samo BT audio« avte (brez AA) ni nobene omejitve.

### 4.15 Stalno poslušanje / wake-word — ❌ (in zavestno opuščeno)

Sistemski `AlwaysOnHotwordDetector` (DSP, varčen) je od Androida 12 rezerviran za sistemske asistente; softversko stalno poslušanje bi pomenilo trajno odprt mikrofon in hitro praznjenje baterije ter se križa z omejitvami iz 4.8. Skladno s tvojo odločitvijo: **samo eksplicitni sprožilci**.

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

**Sprožilci seje** (brez stalnega poslušanja): ploščica/widget/ikona · sistemska asistentska gesta (dolgi pritisk power, poteza, gumb na volanu/slušalkah — od faze 3) · dotik trajnega obvestila (npr. ko smo v avtu) · priklop na avtomobilski Bluetooth zbudi aplikacijo in pripravi sejo (mikrofon se odpre šele ob gesti/dotiku — omejitev OS, glej 4.8).

### 5.1 Android aplikacija — moduli

| Modul | Naloga |
|---|---|
| `VoiceSessionService` | foreground service (tip `microphone`); upravlja sejo: poslušanje → orkestracija → odgovor; drži notifikacijo z live statusom |
| `SpeechIO` | STT (streaming, delni rezultati) in TTS; barge-in (prekinitev TTS, ko uporabnik spregovori) v kasnejši fazi |
| `IntentRouter` | lokalna slovenska gramatika za hitre ukaze (»pokliči X«, »predvajaj glasbo«, »navigiraj domov«, »preberi obvestila«); vse ostalo gre na AI |
| `AgentClient` | WebSocket na backend; pošlje transkript + minimalen kontekst (aktivna obvestila, stanje predvajanja …), sprejme `{say, actions[]}` s streamingom |
| `ActionExecutor` | podmoduli: klici, mediji, navigacija, obvestila, sporočila, sistem |
| `SafetyGate` | deterministična klasifikacija dejanj (tabela v razdelku 6) + glasovni potrditveni dialog; klasifikacije NE dela AI |
| `AssistantService` | `VoiceInteractionService` + `VoiceInteractionSession` (vloga privzetega asistenta) — hrbtenica za mikrofon ob zaklenjenem zaslonu, sistemske geste in zagon dejanj iz ozadja (glej 4.10) |
| `TriggerModule` | Quick Settings ploščica, widget/bližnjica, dotik trajnega obvestila; Bluetooth-v-avtu prek CompanionDeviceManager (samo prebudi aplikacijo — mikrofon vedno prek asistentske seje ali dotika obvestila, glej 4.8/4.9) |
| `NotifCenter` | `NotificationListenerService`: branje, predpomnjenje zadnjih obvestil, iskanje reply-akcij |
| `AuditLog` | lokalna Room baza: čas, transkript, klasifikacija, izid; brez zvočnih posnetkov; izvoz samo ročno |
| `Settings/Onboarding` | vodeno podeljevanje dovoljenj, izbire (potrditvene stopnje, whitelist kontaktov, naslov »domov« …) |

### 5.2 Backend »gateway«

- majhen **TypeScript/Node** (ali Python) proces; teče 24/7 na napravi doma (mini PC, Raspberry Pi 5, stari laptop) ali na malem VPS,
- **edino mesto s ChatGPT/Codex poverilnicami** (OAuth prijava se naredi enkrat, tokene upravlja Codex sam),
- na telefon izpostavljen **izključno prek Tailscale** (WireGuard VPN, brez odprtih portov proti internetu) — priporočena rešitev; alternativa je javni HTTPS z močnim tokenom,
- protokol do appa: en WebSocket, sporočila `user_turn`, `say_delta` (za sproten TTS), `actions`, `session_state`,
- AI vrača **strukturiran JSON po shemi** (`say`, `actions[]`, `needs_confirmation`), backend jo validira; neveljaven output se zavrne in ponovi,
- privzeti model **gpt-5.6-luna z nizkim reasoning effort** (hitrost in varčnost z limiti), eskalacija na višji tier za kompleksne naloge; za ozadje glej razdelek 3,
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
| 1 | dostop do obvestil (sistemska nastavitev) | nadzor glasbe prek `MediaController`; isti dostop v fazi 2 pokrije še branje obvestil |
| 3 | vloga privzetega asistenta (`ROLE_ASSISTANT` — sistemska izbira, ne dovoljenje) | mikrofon ob zaklenjenem zaslonu, sistemske geste, dejanja iz ozadja |
| 4 | `BLUETOOTH_CONNECT`, `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | samodejno prebujenje ob priklopu v avto |
| 5 (opcijsko) | `SEND_SMS` (+ enkratni odklep »Dovoli omejene nastavitve«), `ACCESS_NOTIFICATION_POLICY` | SMS, »ne moti« |

---

## 8. Fazni plan

**MVP = faze 0–2.** Vsaka faza je samostojno uporabna in se konča s preizkusom na pravem telefonu. Vrstni red upošteva ključno ugotovitev iz 4.8/4.10: asistentska vloga (faza 3) je predpogoj za polno avtomobilsko izkušnjo (faza 4).

### Faza 0 — preverba temeljev (prototip, ~nekaj dni)
- na **ciljnem telefonu**: kakovost sl-SI STT (`SpeechRecognizer`, streaming) in TTS glasu; preverba on-device STT paketa (`checkRecognitionSupport`) in sl-SI TTS glasu,
- Xiaomi/HyperOS specifično: nastavitev autostarta in izjem baterije ter preverba, da foreground service preživi zaklep zaslona,
- backend: Codex OAuth prijava + prvi programatski klic; meritev latence konec-govora → prvi-zvok,
- **go/no-go odločitve**: STT/TTS pot za MVP, sprejemljivost latence AI poti.

### Faza 1 — jedro »glas → dejanje« (seja v ospredju)
- foreground service + ploščica/widget za zagon seje (aktivnost je vidna → mikrofon in zagon dejanj brez trikov),
- `IntentRouter` z lokalnimi ukazi: klic, glasba (nadzor prek `MediaController` + poskus »predvajaj X«), navigacija, glasnost, svetilka,
- AI pot prek backenda za vse ostalo (interpretacija + odgovor), streaming odgovora v TTS,
- večturnost **znotraj seje** (follow-up okno poslušanja po odgovoru),
- `SafetyGate` + glasovne potrditve, audit log.

### Faza 2 — obvestila in sporočila (konec MVP)
- `NotificationListenerService`: »preberi obvestila« / »kaj je novega« s povzetkom prek AI (vsebina obvestil označena kot nezaupanja vredna),
- WhatsApp (in drugi messengerji) odgovor prek notification reply akcije: AI sestavi osnutek → prebere naglas → 🟡 potrditev → pošlji; 🧪 test po aplikacijah,
- robovi: obvestilo medtem izginilo / reply akcije ni → pomočnik jasno pove, kaj lahko in česa ne.

### Faza 3 — sistemski asistent (hrbtenica za zaklenjen zaslon)
- `VoiceInteractionService` + `VoiceInteractionSession` (sejno okno čez keyguard) + onboarding za izbiro privzetega asistenta,
- mikrofon ob zaklenjenem telefonu, sprožitev z dolgim pritiskom power / potezo / gumbom na slušalkah,
- klic in navigacija iz ozadja (asistentski BAL privilegij), 🧪 preizkus OEM posebnosti na ciljnem telefonu,
- barge-in (prekinitev TTS z govorom), če se izkaže izvedljivo brez odmeva prek zvočnika.

### Faza 4 — avto
- CompanionDeviceManager asociacija z avtomobilskim Bluetoothom; ob priklopu: prebujenje, priprava seje, trajno obvestilo, glasovni pozdrav prek TTS,
- glasovna seja z gumbom na volanu / asistentsko gesto / enim dotikom obvestila — ob zaklenjenem telefonu,
- »car mode« vedenje: daljše follow-up okno, klici privzeto brez potrditve (nastavljivo), zvok skozi avtomobilski BT,
- obnašanje brez omrežja: lokalni ukazi delujejo, pomočnik pove, da AI ni dosegljiv.

### Faza 5 — poliranje in opcije
- nastavitve (stopnje potrditev, whitelist kontaktov, »domov«/»služba«), izvoz audit loga,
- opcijsko: SMS pošiljanje, »ne moti«, alarmi/opomniki, dodatne sistemske akcije,
- vzdržljivostni testi baterije, zanesljivost CDM sprožilca, dolgotrajni testi WhatsApp reply poti.

---

## 9. Znana tveganja

| # | Tveganje | Resnost | Blažitev |
|---|---|---|---|
| 1 | **Mikrofon čez keyguard na konkretnem OEM** (Samsung/Xiaomi geste, background-kill politike) kljub asistentski vlogi ne deluje gladko | visoka | prototip v fazi 3 na ciljnem telefonu; fallback: dotik obvestila (en tap, brez odklepa) |
| 2 | **Slovenski STT/TTS ni dovolj dober** za prijetno rabo | visoka | faza 0 je namenski go/no-go; nadgradnja na plačljiv Whisper-razred STT; TTS ostane sistemski |
| 3 | **ToS sivo območje** ne-kodirne rabe Codexa; OpenAI lahko zaostri | srednja | osebna raba enega uporabnika; `AiProvider` abstrakcija → preklop na API ključ je konfiguracija; spremljanje ChatGPT Work |
| 4 | **Deljeni limiti porabe** (isti bazen kot tvoja lastna Codex/ChatGPT raba; Plus ima spet 5-urno okno) | srednja | luna model + nizek reasoning; lokalni fast-path zmanjša število AI klicev; branje `rateLimits` in glasovno opozorilo |
| 5 | **WhatsApp reply injekcija se pokvari** ob posodobitvi WhatsAppa | srednja | zajet je le mehanizem obvestil (ni ban tveganja); testi po aplikacijah; jasno sporočilo ob neuspehu |
| 6 | **CDM `onDeviceAppeared` s klasičnim BT** avta ni pravočasen/zanesljiv | srednja | prototip v fazi 4; rezerva: `ACTION_ACL_CONNECTED` receiver; skrajni fallback: ročni zagon pred vožnjo |
| 7 | **Churn Codex površin** (docs migracije, menjave modelov, limitov, eksperimentalne funkcije) | srednja | pripenjanje verzije CLI, regeneracija shem ob nadgradnji, integracijski smoke test |
| 8 | **Latenca AI poti** (govor → odgovor) predolga za pogovorni občutek | srednja | streaming `say` delta → TTS začne ob prvem stavku; luna model; lokalna gramatika za pogoste ukaze |
| 9 | **Backend nedosegljiv** (izpad doma, ni signala v avtu) | nizka | lokalni ukazi delujejo vedno; pomočnik jasno pove, da je »pametni del« offline |

---

## 10. Sprejete odločitve (28. 8. 2026)

Odgovori na odprta vprašanja iz razprave:

| Vprašanje | Odločitev | Posledica za načrt |
|---|---|---|
| Kje teče backend | **naprava doma + Tailscale** | backend ni izpostavljen internetu; Codex CLI + gateway na domači napravi 24/7 |
| ChatGPT naročnina | **Pro** | limiti niso ozko grlo (Pro trenutno izvzet iz 5-urnega okna); *luna* ostane privzeti model zaradi latence, ne varčevanja |
| Ciljni telefon | **Xiaomi / POCO / Redmi** (točen model še sporočiš) | HyperOS agresivno ubija ozadje → onboarding mora vključiti autostart + izjeme baterije; asistentska vloga in CDM sprožilec se preverita na napravi (faze 0/3/4) |
| Avto | **samo Bluetooth zvok** (brez Android Auto) | najčistejši scenarij: CDM prebujenje + zvok prek BT; omejitve Android Auto v celoti odpadejo |
| STT/TTS | **Google brezplačno**, go/no-go test v fazi 0 | 0 €; nadgradnja na plačljiv Whisper/TTS API le, če slovenščina ne zadošča |
| Sivo območje Codexa | **sprejeto** | izključno uradne poti, osebna raba, `AiProvider` rezerva na API ključ |
| Jezik interakcije | **samo slovenščina** | ena lokalna gramatika, STT fiksno sl-SI |
| Ime aplikacije | **v izbiri** (predlogi podani) | repozitorij ostane Android-Agent do odločitve |

**Še odprto:** točen model telefona (potreben za fazo 0) in končno ime aplikacije.
