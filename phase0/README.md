# Faza 0 — preverba temeljev

Cilj (glej [PLAN.md](../PLAN.md), razdelek 8): na **Xiaomi 14 Pro** preveriti slovenski govor
(STT/TTS) in obnašanje HyperOS v ozadju, na domači napravi pa Codex prijavo in latenco.
Rezultat je go/no-go odločitev pred gradnjo prave aplikacije.

## A · Telefon — aplikacija »Sopotnik faza 0«

APK zgradi GitHub Actions (`.github/workflows/phase0-apk.yml`) in ga pripne v
`phase0/apk/sopotnik-faza0-debug.apk` — Claude ti ga pošlje tudi neposredno v pogovor.

**Namestitev:** odpri APK na telefonu → HyperOS bo vprašal za dovoljenje za namestitev
iz neznanega vira → dovoli za to aplikacijo (Nastavitve → Zaščita in zasebnost →
Namestitev neznanih aplikacij, če ne vpraša sam).

**Pred testi (HyperOS!):** dolg pritisk na ikono → Podatki o aplikaciji →
1. **Samodejni zagon (Autostart): VKLOPI**
2. **Varčevanje z baterijo → Brez omejitev**

**Testi (v aplikaciji, po vrsti):**
1. **STT:** pritisni 🎤 in povej nekaj stavkov v slovenščini (npr. »Pokliči Marka in mu povej,
   da zamujam petnajst minut«). Preveri natančnost in izpisano zakasnitev. Ponovi 3–5×,
   tudi s šumniki in imeni. Nato označi »Zahtevaj offline« in poskusi še enkrat —
   to pove, ali telefon premore slovensko prepoznavo brez interneta.
2. **TTS:** pritisni 🔊 — ali je slovenščina razumljiva? Če piše, da glas manjka,
   pritisni »Namesti slovenski glas«.
3. **Test ozadja:** pritisni »Zaženi«, **zakleni telefon za 5–10 minut**, odkleni,
   pritisni »Osveži«. ✅ pomeni, da je storitev preživela; ⚠️ pomeni poseg HyperOS
   (v tem primeru preveri točki 1 in 2 zgoraj in ponovi).
4. **Poročilo:** pritisni »📋 Kopiraj poročilo« in ga prilepi v pogovor s Claudom.

## B · Domača naprava — backend proba

Navodila v [backend/README.md](backend/README.md) (Node 20+, `npm install`,
`npx codex login --device-auth`, `node probe.mjs`). Izpis prilepi v pogovor.

## C · Merila go/no-go

| Preverba | Cilj |
|---|---|
| STT natančnost (sl) | razumljivi vsakdanji ukazi, imena kontaktov vsaj približno |
| STT zakasnitev | < ~1,5 s od konca govora do rezultata |
| STT offline | zaželeno; če ni podprto, lokalni ukazi v avtu brez signala odpadejo (dokumentiramo) |
| TTS razumljivost | jasna slovenščina; »robotskost« je sprejemljiva za MVP |
| Test ozadja | ✅ brez vrzeli ob pravilnih HyperOS nastavitvah |
| Codex proba | prijava deluje; topla nit < ~5 s do konca odgovora |
