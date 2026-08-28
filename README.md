# Sopotnik (Android-Agent)

Zasebni osebni AI pomočnik za Android s klicnim imenom **Sven** — voice-first, uporaben tudi v avtu in ob zaklenjenem zaslonu (kolikor Android dovoljuje). AI plast (ChatGPT/Codex prek uradnega OAuth) razume jezik in odloča, Android plast izvaja dejanja (klici, WhatsApp, glasba, navigacija, obvestila).

Načrt, študija izvedljivosti in fazni plan: **[PLAN.md](PLAN.md)**.

| Mapa | Vsebina |
|---|---|
| `android/` | aplikacija Sopotnik (Kotlin; APK gradi GitHub Actions → `builds/`) |
| `backend/` | gateway za Mac mini (WebSocket ↔ Codex, ChatGPT naročnina) |
| `phase0/` | zaključeni testi izvedljivosti (govor, HyperOS, Codex latenca) |
