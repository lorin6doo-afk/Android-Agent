# Faza 0 — backend proba (Codex + latenca)

Cilj: na napravi, ki bo doma poganjala backend (mini PC / RPi / star laptop), preveriti, da
ChatGPT/Codex prijava deluje, in izmeriti latenco odgovorov.

## Predpogoji

- Linux ali macOS z **Node.js 20+** (`node --version`)
- tvoja ChatGPT **Pro** prijava (prek brskalnika na telefonu ali računalniku)

## Koraki

```bash
cd phase0/backend
npm install                       # namesti Codex SDK + CLI (binarko prinese s sabo)
npx codex login --device-auth     # izpiše URL + kodo -> odpri na telefonu in potrdi
node probe.mjs                    # dva testna obrata + meritve
```

- `npx codex login status` pokaže, ali si prijavljen.
- Če je `--device-auth` na računu onemogočen, alternativi (obe uradni):
  1. prijava na namizju z brskalnikom (`npx codex login`) in kopiranje `~/.codex/auth.json` na strežnik,
  2. SSH tunel: `ssh -L 1455:localhost:1455 uporabnik@streznik`, nato `npx codex login` na strežniku.

## Kaj mi sporočiš nazaj

Izpis probe (odgovora + čase). Zanima naju: hladen start, topla nit in ali je model
`gpt-5.6-luna` na voljo (če ne, proba sama pade nazaj na privzeti model in to izpiše).

## Tailscale (priprava za fazo 1 — ni obvezno za probo)

1. https://tailscale.com — brezplačen račun (do 3 uporabniki / 100 naprav)
2. na strežniku: `curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up`
3. na telefonu: aplikacija Tailscale iz Play Store, prijava z istim računom
4. telefon bo backend videl na naslovu `100.x.y.z` (ali imenu naprave prek MagicDNS) — brez odpiranja portov v domačem usmerjevalniku
