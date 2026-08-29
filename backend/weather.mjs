// Vremensko orodje za Svena — Open-Meteo (brezplačno, brez ključa).

const WMO = new Map([
  [0, "jasno"], [1, "pretežno jasno"], [2, "delno oblačno"], [3, "oblačno"],
  [45, "megla"], [48, "megla z ivjem"],
  [51, "rahlo pršenje"], [53, "pršenje"], [55, "močno pršenje"],
  [56, "ledeno pršenje"], [57, "močno ledeno pršenje"],
  [61, "rahel dež"], [63, "dež"], [65, "močan dež"],
  [66, "leden dež"], [67, "močan leden dež"],
  [71, "rahlo sneženje"], [73, "sneženje"], [75, "močno sneženje"], [77, "zrnat sneg"],
  [80, "rahle plohe"], [81, "plohe"], [82, "močne plohe"],
  [85, "snežne plohe"], [86, "močne snežne plohe"],
  [95, "nevihta"], [96, "nevihta s točo"], [99, "močna nevihta s točo"],
]);

const describe = (code) => WMO.get(code) ?? "spremenljivo";

export async function getWeather(cityRaw) {
  const city = (cityRaw || process.env.SOPOTNIK_CITY || "").trim();
  if (!city) return "Ni jasno, za kateri kraj — vprašaj uporabnika.";

  const geoRes = await fetch(
    `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=sl&format=json`
  );
  const geo = await geoRes.json();
  const place = geo?.results?.[0];
  if (!place) return `Kraja »${city}« ne najdem.`;

  const wxRes = await fetch(
    `https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}` +
    `&current=temperature_2m,weather_code,wind_speed_10m` +
    `&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code` +
    `&timezone=auto&forecast_days=2`
  );
  const wx = await wxRes.json();
  const cur = wx?.current;
  const d = wx?.daily;
  if (!cur || !d) return "Vremenskih podatkov trenutno ni mogoče pridobiti.";

  const parts = [
    `V kraju ${place.name} je trenutno ${Math.round(cur.temperature_2m)} stopinj, ${describe(cur.weather_code)}, veter ${Math.round(cur.wind_speed_10m)} kilometrov na uro.`,
    `Danes od ${Math.round(d.temperature_2m_min[0])} do ${Math.round(d.temperature_2m_max[0])} stopinj, ${describe(d.weather_code[0])}, verjetnost padavin ${d.precipitation_probability_max[0] ?? 0} odstotkov.`,
    `Jutri od ${Math.round(d.temperature_2m_min[1])} do ${Math.round(d.temperature_2m_max[1])} stopinj, ${describe(d.weather_code[1])}.`,
  ];
  return parts.join(" ");
}
