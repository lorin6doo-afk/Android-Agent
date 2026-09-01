# Sopotnik — ops inbox (oblak → Mac)

Navodila oblačne razvojne seje za lokalnega operaterja (Claude seja na Mac mini).
Vrinjena sporočila (fire_trigger) do lokalnih sej trenutno ne pridejo skozi, zato
ta datoteka služi kot enosmerni kanal: oblak jo spreminja s pushi, Mac jo bere.

## Pravila za operaterja
- Naloge imajo ID (T1, T2 …); najnovejše so spodaj. Izvedi vse, ki jih še ni v `ops/done.local`.
- Po vsaki izvedeni nalogi dopiši njen ID v `ops/done.local` (ena vrstica = en ID).
  Datoteka je lokalna — je v .gitignore in je NE commitaš.
- NE commitaj in NE pushaj ničesar (kodo in to datoteko ureja izključno oblačna seja),
  z EDINO izjemo: poročila o rezultatih v `ops/results/*.md` — commit sporočilo
  »ops: rezultati [skip ci]«, pred pushem OBVEZNO `git pull --rebase`. Nič drugega.
- NE uporabljaj interaktivnih vprašanj (AskUserQuestion) — terminala morda nihče ne gleda.
- Vse ugotovitve in surove izpise objavi v pogovor; ZADNJI stavek odgovora naj bo:
  »OPS <ID-ji> KONČANO: <enovrstični povzetek>« — oblačna seja ga prebere iz povzetka seje.
- Po obdelavi nalog vedno znova zaženi stražarja (glej dno datoteke).

## T1 — identifikacija USB naprav
`adb devices -l`, nato za VSAKO napravo (`adb -s <serial>`):
`getprop ro.product.manufacturer`, `getprop ro.product.model`, `getprop ro.product.marketname`,
`getprop ro.build.version.release`, `settings get global device_name`.
Če je naprava »unauthorized«: v pogovor napiši, naj uporabnik na tistem telefonu potrdi
»Dovoli odpravljanje napak USB«, in ponovi čez ~30 s (do 3×).
V poročilu jasno loči: kateri serial je uporabnikov Xiaomi 14 in kateri je TESTNI telefon.

## T2 — gateway na v0.3.5
`tail -2 backend/gateway.log` — če ne piše »Sopotnik gateway v0.3.5«, poženi
`launchctl kickstart -k gui/$UID/si.sopotnik.gateway`, počakaj 2 s in spet `tail -2 backend/gateway.log`.
(git pull je ob branju te datoteke že narejen.)

## T3 — priprava TESTNEGA telefona (tisti, ki NI uporabnikov Xiaomi 14)
1. `adb -s <testni> install -r builds/sopotnik-debug.apk`
   in `adb -s <testni> shell dumpsys package si.sopotnik | grep -m1 versionName`
2. Dostop do obvestil brez ročnih tapov:
   `adb -s <testni> shell cmd notification allow_listener si.sopotnik/si.sopotnik.NotifListener`
   (če ukaz javi napako, jo navedi v poročilu — potem bo dostop treba vklopiti ročno na telefonu)
3. `adb -s <testni> logcat -c`
4. `adb -s <testni> shell am start -n si.sopotnik/.MainActivity` in počakaj 6 s
5. `adb -s <testni> shell "cmd notification post -t 'Test Sopotnik' TagX 'Testno obvestilo'"` in počakaj 3 s
6. `adb -s <testni> shell am broadcast -a si.sopotnik.DEBUG_DUMP si.sopotnik` in počakaj 2 s
7. `adb -s <testni> logcat -v time -s Sopotnik:* -d` → v poročilo daj vrstice
   »poslušalec obvestil POVEZAN«, »obhod: …« in vse »DEBUG_DUMP: …« (dostop, vezan, števili obvestil).

## T4 — dokončanje priprave testnega telefona (po vklopu »Namestitev prek USB«)
Uporabnik je trenutno NA DALJAVO, zato stikalo najprej poskusi vklopiti SAM prek adb + posnetkov zaslona:
0. `adb -s <testni> shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS`,
   nato `adb -s <testni> exec-out screencap -p > /tmp/testni.png` in si posnetek OGLEJ (Read).
   Poišči »Namestitev prek USB« / »Install via USB« (po potrebi se pomikaj:
   `adb shell input swipe 500 1600 500 600`), stikalo tapni (`adb shell input tap X Y`),
   potrpežljivo potrdi morebitni MIUI dialog z odštevalnikom (po ~11 s znova screencap + tap na potrditev).
   Po vsakem koraku preveri stanje z novim posnetkom. Če MIUI zahteva Mi račun ali SIM,
   tega NE poskušaj urejati — v poročilu navedi točno besedilo dialoga in pusti uporabniku.
(Ročna pot, če samodejna ne uspe: uporabnik doma vklopi Nastavitve → Razvijalske možnosti → »Namestitev prek USB«.)
1. Poskusi `adb -s <testni> install -r builds/sopotnik-debug.apk`; če spodleti, poskušaj znova
   vsakih 60 s, skupno do 15 minut. Izpiši celoten razlog ob prvem in zadnjem neuspehu.
2. Ko namestitev uspe, nadaljuj s koraki 2–7 iz naloge T3 (allow_listener, logcat -c, am start,
   testno obvestilo, DEBUG_DUMP, izpis logcata).
3. V istem odgovoru ponovi še kratko identifikacijo naprav iz T1 (serial → znamka, model, ime,
   Android) in stanje gatewaya iz T2 (verzija iz backend/gateway.log).
4. ZADNJI stavek: »OPS T4 KONČANO: APK <verzija>; listener vezan <da/ne>; obvestil <N/M>;
   NAPRAVE: <…>; GATEWAY: v<…>« oziroma »OPS T4 BLOKIRANO: <razlog>«.

## T4b — namestitev BREZ stikala »Namestitev prek USB« (SIM ni potreben!)
MIUI za vklop stikala zahteva SIM, ki ga v testnem telefonu ni — zato namesti prek
telefonovega upravitelja datotek (ta poti ne omejuje):
1. `adb -s <testni> push builds/sopotnik-debug.apk /sdcard/Download/sopotnik.apk`
2. Ugotovi paket upravitelja datotek: `adb -s <testni> shell pm list packages | grep -iE "fileexplorer|filemanager|files|documentsui"`
   in mu vnaprej dovoli nameščanje: `adb -s <testni> shell appops set <paket> REQUEST_INSTALL_PACKAGES allow`
3. Odpri upravitelja datotek (`adb shell monkey -p <paket> 1` ali am start), nato s
   screencap + `input tap` navigiraj: mapa Prenosi/Download → sopotnik.apk → »Namesti« →
   potrdi morebitne dialoge. Po VSAKEM tapu nov screencap in preveri stanje.
4. Preveri namestitev: `adb -s <testni> shell dumpsys package si.sopotnik | grep -m1 versionName`
   (pričakovano 0.3.5-faza2 — builds/ je po git pullu svež).
5. Nadaljuj s koraki 2–7 iz T3 (allow_listener, testno obvestilo, DEBUG_DUMP, logcat).
6. Poročaj kot v T4 (zadnji stavek »OPS T4 KONČANO: …« z APK verzijo, listener stanjem,
   N/M, napravami in gateway verzijo). Če tudi ta pot spodleti, navedi TOČNO besedilo napake.

## T4c — namestitev na testnem telefonu je do fizičnega obiska USTAVLJENA
Ugotovitev T4/T4b: MIUI brez stikala »Odpravljanje napak USB (varnostne nastavitve)« blokira
`input tap` (SecurityException INJECT_EVENTS) in `pm install` — obojega se na daljavo NE DA vklopiti.
APK je že potisnjen na /sdcard/Download/sopotnik.apk. Namestitve NE poskušaj več; počakaj.
Ko bo uporabnik fizično pri telefonu, bo: (1) tapnil sopotnik.apk v mapi Prenosi in ga namestil,
ali še bolje (2) začasno vstavil SIM, se prijavil v Mi račun in vklopil OBE stikali:
»Namestitev prek USB« in »Odpravljanje napak USB (varnostne nastavitve)« — potem lahko SIM odstrani,
nastavitvi ostaneta in telefon postane polno avtomatiziran (install + tapkanje prek adb).
Do takrat na testnem telefonu smeš uporabljati le: screencap, logcat, am start/broadcast,
cmd notification post — brez input tap/swipe in brez install poskusov.

## T5 — testni telefon ODKLENJEN: izvedi celotno pripravo (T4c ne velja več)
Uporabnik je pravkar: vstavil SIM, vklopil »Namestitev prek USB« in verjetno tudi
»Odpravljanje napak USB (varnostne nastavitve)«. Telefon je na USB. Izvedi:
1. `adb devices -l` — potrdi, da je testni telefon viden (če je »unauthorized«, poročaj).
2. `adb -s <testni> install -r builds/sopotnik-debug.apk`
   nato `adb -s <testni> shell dumpsys package si.sopotnik | grep -m1 versionName` (pričakovano 0.3.5-faza2).
3. Preveri, ali zdaj deluje vnos prek adb: `adb -s <testni> shell input keyevent 82` —
   če vrže SecurityException INJECT_EVENTS, v poročilo zapiši, da drugo stikalo
   (»Odpravljanje napak USB (varnostne nastavitve)«) še NI vklopljeno — uporabnik naj ga vklopi,
   DOKLER JE SIM ŠE VSTAVLJEN.
4. Dostop do obvestil: `adb -s <testni> shell cmd notification allow_listener si.sopotnik/si.sopotnik.NotifListener`
5. `adb -s <testni> logcat -c`; `adb -s <testni> shell am start -n si.sopotnik/.MainActivity`; počakaj 6 s.
6. `adb -s <testni> shell "cmd notification post -t 'Test Sopotnik' TagX 'Testno obvestilo'"`; počakaj 3 s.
7. `adb -s <testni> shell am broadcast -a si.sopotnik.DEBUG_DUMP si.sopotnik`; počakaj 2 s.
8. `adb -s <testni> logcat -v time -s Sopotnik:* -d` → v poročilo VSE vrstice
   (»poslušalec obvestil POVEZAN«, »obhod: …«, »DEBUG_DUMP: …«).
9. Ponovi še identifikacijo naprav (serial → znamka, model, Android) in verzijo gatewaya
   (`tail -2 backend/gateway.log`).
10. ZADNJI stavek natanko: »OPS T5 KONČANO: APK <verzija>; listener vezan <da/ne>;
    obvestil <N/M>; inject <deluje/blokiran>; NAPRAVE: <…>; GATEWAY: v<…>«
    oziroma »OPS T5 BLOKIRANO: <točen razlog>«.

## T6 — STALNA naloga: samodejni testni krog ob vsakem novem APK
Velja trajno. Ob vsakem novem commitu »ci: build Sopotnik APK« (stražar ga zazna):
1. `adb -s <testni> install -r builds/sopotnik-debug.apk`
2. `adb -s <testni> shell am start -n si.sopotnik/.MainActivity`; počakaj 6 s
3. `adb -s <testni> shell am broadcast -a si.sopotnik.DEBUG_DUMP si.sopotnik`; počakaj 2 s
4. `adb -s <testni> logcat -v time -s Sopotnik:* -d | tail -30`
5. Kratko poročilo; ZADNJI stavek: »OPS T6 KONČANO: APK <verzija>; listener <da/ne>; obvestil <N/M>«.
Stražarja po VSAKI obdelavi znova zaženi SAM, brez spraševanja — to je stalno pravilo in ne potrebuje potrditve.

## T7 — konfiguracija Sopotnika na testnem telefonu (konec-do-konca)
Testni telefon ima zdaj nameščen Tailscale (uporabnik ga je prijavil), zato lahko doseže gateway.
1. Dovoljenja aplikaciji: `adb -s <testni> shell pm grant si.sopotnik android.permission.RECORD_AUDIO`
   in enako za `android.permission.POST_NOTIFICATIONS`, `android.permission.READ_CONTACTS`, `android.permission.CALL_PHONE`.
2. Povezljivost: `adb -s <testni> shell ping -c 2 100.118.155.97` — poročaj izid.
3. Nastavi backend v aplikaciji: `adb -s <testni> shell am start -n si.sopotnik/.SettingsActivity`,
   s screencap + `input tap` izberi polje naslova in z `input text` vnesi `ws://100.118.155.97:8787`;
   v polje žetona vnesi vrednost SOPOTNIK_TOKEN iz `backend/.env` (preberi jo lokalno —
   ŽETONA NIKOLI ne izpisuj v pogovor ali poročilo!). Tapni »Preizkusi povezavo« in s
   screencap preveri izid; nastavitve se shranijo ob izhodu (keyevent 4).
4. Dimni test: `adb -s <testni> shell am start -n si.sopotnik/.MainActivity --ez autostart true`;
   počakaj 10 s; `adb -s <testni> logcat -d -s Sopotnik:* | tail -20` — uspeh je vrstica o
   pripravljeni seji ali poslušanju brez napak povezave. Nato `adb -s <testni> shell am force-stop si.sopotnik`.
5. ZADNJI stavek: »OPS T7 KONČANO: ping <ok/ne>; test povezave <ok/ne>; seja <ok/ne>« (+ razlog ob neuspehu).

## T8 — posodobitev gatewaya na v0.3.7 (novo orodje open_notification)
`launchctl kickstart -k gui/$UID/si.sopotnik.gateway`, počakaj 2 s in preveri
`tail -2 backend/gateway.log` — pričakovano »Sopotnik gateway v0.3.7 posluša …«.
(Restart za trenutek prekine morebitno živo sejo — aplikacija se sama znova poveže.)
ZADNJI stavek: »OPS T8 KONČANO: gateway v<verzija>«.

## T9 — posodobitev gatewaya na v0.3.8 (varnostna zapora prejemnika + compose_message)
`launchctl kickstart -k gui/$UID/si.sopotnik.gateway`, počakaj 2 s in `tail -2 backend/gateway.log`
— pričakovano »Sopotnik gateway v0.3.8 posluša …«. ZADNJI stavek: »OPS T9 KONČANO: gateway v<verzija>«.

## T10 — baterija testov iskanja stikov na TESTNEM telefonu (v0.3.10) — IZVEDI TAKOJ
POZOR: APK 0.3.10 je CI že zgradil (commit »ci: build Sopotnik APK«, 15:36). Če si T10 začel
prezgodaj (pred APK), ga izvedi ZDAJ v celoti in poročaj NATANKO v predpisanem formatu.
Predpogoj: počakaj, da je v builds/ APK z versionName 0.3.10-faza2 (git pull vsakih 30 s;
T6 ga bo namestil — sicer ga namesti sam z `adb -s <testni> install -r builds/sopotnik-debug.apk`
in preveri z `dumpsys package si.sopotnik | grep -m1 versionName`). Nato:
0. `launchctl kickstart -k gui/$UID/si.sopotnik.gateway` in preveri »gateway v0.3.9« v logu.
1. Na testnem telefonu USTVARI te stike (adb INSERT + screencap + input tap na Shrani;
   po vsakem preveri s screencap):
   `adb -s <testni> shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact -e name "<IME>" -e phone "<ŠT>"`
   · »Urša*« +38640111222 · »Bojan Urša« +38640111333 · »Rudi Švikart« +38640111444
   · »Mama ❤« +38640111555 · »dr. Novak Jože« +38640111666 · »Miha K.« +38640111777
   · »Novakovič Peter« +38640111888
2. Preveri dovoljenje: `adb -s <testni> shell pm grant si.sopotnik android.permission.READ_CONTACTS`
3. `adb -s <testni> logcat -c`, nato za VSAKO poizvedbo:
   `adb -s <testni> shell am broadcast -a si.sopotnik.DEBUG_DUMP --es q "<POIZVEDBA>" si.sopotnik`
   Poizvedbe in PRIČAKOVANI PRVI zadetek:
   · "Urša Zvezdica" → Urša*      · "Urša" → Urša*          · "ursa" → Urša*
   · "Svikart" → Rudi Švikart     · "Rudi" → Rudi Švikart   · "Bojan" → Bojan Urša
   · "Novak Jože" → dr. Novak Jože · "Jože Novak" → dr. Novak Jože · "Novakovo" → dr. Novak Jože
   · "Novak" → dr. Novak Jože (PRED Novakovič Peter; Miha K. NE sme biti med zadetki)
   · "Mama" → Mama ❤              · "mama srce" → Mama ❤
   · "Janez" → NI ZADETKA (Miha K. ne sme zadeti) · "Katarina Kobilca" → NI ZADETKA
   · "Ur" → več kandidatov (Urša* in Bojan Urša)
4. `adb -s <testni> logcat -v time -s Sopotnik:* -d | grep DEBUG_FIND` → primerjaj z zgornjimi
   pričakovanji in za vsako vrstico zapiši PASS/FAIL.
5. Poročilo: tabela PASS/FAIL + surove DEBUG_FIND vrstice.
   ZADNJI stavek natanko: »OPS T10 KONČANO: <N>/15 PASS« oziroma »OPS T10 BLOKIRANO: <razlog>«.

## T10b — PONOVI baterijo NA APK 0.3.10 (rezultati T10 so bili iz stare 0.3.9!)
Tvoje poročilo »7/15, sort + threshold« je nastalo pred izgradnjo 0.3.10 — testiral si staro kodo.
1. NAJPREJ preveri verzijo: `adb -s <testni> shell dumpsys package si.sopotnik | grep -m1 versionName`
   — če NI »0.3.10-faza2«: `git pull --ff-only` in `adb -s <testni> install -r builds/sopotnik-debug.apk`,
   nato verzijo preveri ZNOVA in jo OBVEZNO navedi v poročilu.
2. Preveri, da testni stiki iz T10 obstajajo (poženi DEBUG_FIND "Rudi" — če vrne NI ZADETKA,
   stike ustvari po postopku iz T10, točka 1).
3. Izvedi VSEH 15 poizvedb iz T10 (točka 3) in oceni PASS/FAIL po tam navedenih pričakovanjih.
4. ZADNJI stavek natanko: »OPS T10b KONČANO: APK <verzija>; <N>/15 PASS« (ob neuspehu BLOKIRANO + razlog).

## T10c — skriptirana baterija + poročilo v repo (nadomesti T10b, če ta še ni tekel na 0.3.10)
1. Če `builds/sopotnik-debug.apk` po `git pull` ni nameščen na testnem telefonu kot
   0.3.10-faza2 (`dumpsys package si.sopotnik | grep versionName`), ga namesti (`install -r`).
2. Poženi: `bash ops/t10.sh <serial-testnega-telefona>` in CELOTEN izpis shrani v
   `ops/results/t10c.md` (dodaj še vrstico z datumom in serijsko naprave).
3. Commitaj in pushni SAMO to datoteko po izjemi iz pravil (»ops: rezultati [skip ci]«,
   prej `git pull --rebase`).
4. ZADNJI stavek: »OPS T10c KONČANO: APK <verzija>; izpis commitan« (ali BLOKIRANO + razlog).
PASS/FAIL presoja NI tvoja naloga — oceni jo oblačna seja iz surovega izpisa.

## T11 — gateway v0.3.11 + dimni test SMS/WhatsApp osnutkov na testnem telefonu
1. `launchctl kickstart -k gui/$UID/si.sopotnik.gateway`; `tail -2 backend/gateway.log` → mora pisati v0.3.11.
2. Počakaj na APK 0.3.11-faza2 v builds/ (git pull na ~30 s) in ga namesti na testni telefon.
3. Dimni testi (surove izpise shrani v `ops/results/t11.md` in commitaj po izjemi):
   a) `adb -s <testni> shell am start -a android.intent.action.SENDTO -d "smsto:%2B38640111222" --es sms_body "Testni osnutek"`
      nato `adb -s <testni> shell "dumpsys activity activities | grep -m2 -iE 'topResumedActivity|mResumedActivity'"`
      → mora biti SMS aplikacija (messaging); screencap za dokaz, da je besedilo v polju.
   b) `adb -s <testni> shell am force-stop si.sopotnik`; zaženi Sopotnik; prek DEBUG ni mogoče klicati orodij —
      zato samo preveri, da se aplikacija zažene brez sesutja po novem buildu (logcat -s Sopotnik:* -d | tail -20).
4. ZADNJI stavek: »OPS T11 KONČANO: gateway v<verzija>; APK <verzija>; smsto <ok/ne>« (ali BLOKIRANO + razlog).

## Stražar (po vsaki obdelavi nalog ga znova zaženi)
V ozadju (Bash, run_in_background):
`while :; do git fetch -q origin claude/android-ai-assistant-plan-88kykl; [ "$(git rev-parse origin/claude/android-ai-assistant-plan-88kykl)" != "$(git rev-parse HEAD)" ] && exit 0; sleep 60; done`
Ko se stražar konča (nov commit v veji): `git pull --ff-only`, znova preberi to datoteko,
izvedi naloge, ki jih ni v `ops/done.local`, in stražarja ponovno zaženi.
