// Проксі для веб-версії «Відбою»: siren.pp.ua та ubilling не віддають CORS-заголовки,
// тому браузер не може читати їх напряму. Відповіді кешуються на краю Cloudflare,
// тож скільки б людей не відкрили сайт, до джерел ідуть лічені запити на хвилину.
//
// Крім того, сервер стежить за тривогою замість призупиненої сторінки й надсилає push-сповіщення
// (таблиця watches у D1, перевірка щохвилини за розкладом cron).

import { describeShift, evaluateNightRules, formatAt, historyIntervals } from "../../web/nightrule.js";
import { sendPush } from "./push.js";

const ROUTES = {
  "/alerts": { url: "https://siren.pp.ua/api/v3/alerts", ttl: 15 },
  "/regions": { url: "https://siren.pp.ua/api/v3/regions", ttl: 86400 },
  "/ubilling": { url: "https://ubilling.net.ua/aerialalerts/", ttl: 15 },
};
const HISTORY_URL = "https://siren.pp.ua/api/v3/alerts/regionHistory?regionId=";

const ALLOWED_ORIGINS = [
  "https://vidbiy.ishawyha.dev",
  "https://olexiyodarchuk.github.io",
  "http://localhost:8000",
  "http://127.0.0.1:8000",
];

const MIN = 60_000;
const WATCH_MAX_AGE = 2 * 24 * 60 * MIN;
const MAX_BODY = 64 * 1024;

function corsHeaders(origin) {
  const allowed = ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0];
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Vary": "Origin",
  };
}

const json = (data, origin, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders(origin), "Content-Type": "application/json", "Cache-Control": "no-store" },
  });

async function proxy(url, ttl, origin) {
  let upstream;
  try {
    upstream = await fetch(url, {
      headers: { "User-Agent": "Vidbiy-web-proxy" },
      cf: { cacheTtl: ttl, cacheEverything: true },
    });
  } catch {
    return new Response("Upstream unavailable", { status: 502, headers: corsHeaders(origin) });
  }
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      ...corsHeaders(origin),
      "Content-Type": upstream.headers.get("Content-Type") || "application/json",
      "Cache-Control": `public, max-age=${Math.min(ttl, 15)}`,
    },
  });
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }
    const url = new URL(request.url);

    if (url.pathname === "/push/watch" && (request.method === "POST" || request.method === "DELETE")) {
      return handleWatch(request, env, origin);
    }
    if (request.method !== "GET") {
      return new Response("Not found", { status: 404, headers: corsHeaders(origin) });
    }
    if (url.pathname === "/push/key") {
      return json({ key: env.VAPID_PUBLIC_KEY }, origin);
    }
    // Історія тривог для правил нічної тривоги: лише числовий regionId, щоб не проксіювати що завгодно.
    if (url.pathname === "/history") {
      const id = url.searchParams.get("regionId") || "";
      if (!/^\d{1,6}$/.test(id)) return new Response("Bad regionId", { status: 400, headers: corsHeaders(origin) });
      return proxy(HISTORY_URL + id, 60, origin);
    }
    const route = ROUTES[url.pathname];
    if (!route) {
      return new Response("Not found", { status: 404, headers: corsHeaders(origin) });
    }
    return proxy(route.url, route.ttl, origin);
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(checkWatches(env));
  },
};

// ---------- Реєстрація очікування ----------

async function sha256hex(text) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const isPushEndpoint = (s) => {
  try {
    return new URL(s).protocol === "https:";
  } catch {
    return false;
  }
};

async function handleWatch(request, env, origin) {
  let body;
  try {
    const text = await request.text();
    if (text.length > MAX_BODY) throw new Error("too large");
    body = JSON.parse(text);
  } catch {
    return json({ error: "bad request" }, origin, 400);
  }

  if (request.method === "DELETE") {
    if (typeof body.endpoint !== "string") return json({ error: "bad request" }, origin, 400);
    await env.DB.prepare("DELETE FROM watches WHERE id = ?").bind(await sha256hex(body.endpoint)).run();
    return json({ ok: true }, origin);
  }

  const sub = body.subscription;
  const w = body.watch;
  if (!sub || !isPushEndpoint(sub.endpoint) || !sub.keys?.p256dh || !sub.keys?.auth || !w?.region || !w.session) {
    return json({ error: "bad request" }, origin, 400);
  }
  const config = {
    region: {
      sirenId: w.region.sirenId ?? null,
      ubilling: w.region.ubilling ?? null,
      name: String(w.region.name || ""),
      up: Array.isArray(w.region.up) ? w.region.up.map(String) : [],
      down: Array.isArray(w.region.down) ? w.region.down.map(String) : [],
    },
    cutoffAt: Number(w.cutoffAt) || 0,
    scheduled: !!w.scheduled,
    label: String(w.label || ""),
    stableMs: Number(w.stableMs) || 0,
    nightRules: Array.isArray(w.nightRules) ? w.nightRules : [],
    tz: String(w.tz || "Europe/Kyiv"),
  };
  const now = Date.now();
  // Той самий сеанс: не губимо те, що сервер побачив сам (тривога, перенесення будильника),
  // поки сторінка спала. Новий сеанс — усе з нуля.
  await env.DB.prepare(`
    INSERT INTO watches (id, endpoint, p256dh, auth, session, config, saw_alert, clear_since, schedule_at, shifted, failing_since, created_at, updated_at)
    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8, ?9, 0, ?10, ?10)
    ON CONFLICT(id) DO UPDATE SET
      endpoint = excluded.endpoint, p256dh = excluded.p256dh, auth = excluded.auth, config = excluded.config,
      updated_at = excluded.updated_at,
      saw_alert = CASE WHEN session = excluded.session THEN MAX(saw_alert, excluded.saw_alert) ELSE excluded.saw_alert END,
      clear_since = CASE WHEN session = excluded.session THEN clear_since ELSE 0 END,
      schedule_at = CASE WHEN session = excluded.session AND shifted = 1 AND excluded.shifted = 0 THEN schedule_at ELSE excluded.schedule_at END,
      shifted = CASE WHEN session = excluded.session THEN MAX(shifted, excluded.shifted) ELSE excluded.shifted END,
      failing_since = CASE WHEN session = excluded.session THEN failing_since ELSE 0 END,
      created_at = CASE WHEN session = excluded.session THEN created_at ELSE excluded.created_at END,
      session = excluded.session
  `).bind(
    await sha256hex(sub.endpoint), sub.endpoint, sub.keys.p256dh, sub.keys.auth, Number(w.session),
    JSON.stringify(config), w.sawAlert ? 1 : 0, Number(w.scheduleAt) || 0, w.shifted ? 1 : 0, now,
  ).run();
  return json({ ok: true }, origin);
}

// ---------- Перевірка щохвилини ----------

async function fetchJSON(url) {
  const res = await fetch(url, { headers: { "User-Agent": "Vidbiy-push" }, cf: { cacheTtl: 15, cacheEverything: true } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Стан тривоги для місця так само, як на сторінці (fetchStatus в app.js); null — невідомо. */
function statusFor(region, sirenAlerts, ubilling) {
  if (region.sirenId && sirenAlerts) {
    const up = new Set(region.up);
    const down = new Set(region.down);
    let partial = false;
    for (const a of sirenAlerts) {
      if (!a.activeAlerts?.length) continue;
      if (a.regionId === region.sirenId || up.has(a.regionId)) return "ACTIVE";
      if (down.has(a.regionId)) partial = true;
    }
    return partial ? "PARTIAL" : "NONE";
  }
  const state = region.ubilling ? ubilling?.states?.[region.ubilling] : null;
  if (typeof state?.alertnow === "boolean") return state.alertnow ? "ACTIVE" : "NONE";
  return null;
}

async function checkWatches(env) {
  const { results: rows } = await env.DB.prepare("SELECT * FROM watches").all();
  if (!rows.length) return;
  const now = Date.now();
  const vapid = {
    publicKey: env.VAPID_PUBLIC_KEY,
    privateJwk: JSON.parse(env.VAPID_PRIVATE_KEY),
    subject: env.VAPID_SUBJECT,
  };

  // Джерела запитуємо один раз на всі очікування.
  const [sirenAlerts, ubilling] = await Promise.all([
    fetchJSON(ROUTES["/alerts"].url).catch(() => null),
    fetchJSON(ROUTES["/ubilling"].url).catch(() => null),
  ]);
  const histories = new Map();
  const history = (id) => {
    if (!histories.has(id)) histories.set(id, fetchJSON(HISTORY_URL + id));
    return histories.get(id);
  };

  const remove = (row) => env.DB.prepare("DELETE FROM watches WHERE id = ?").bind(row.id).run();
  const update = (row, fields) => {
    const keys = Object.keys(fields);
    return env.DB.prepare(`UPDATE watches SET ${keys.map((k) => `${k} = ?`).join(", ")} WHERE id = ? AND session = ?`)
      .bind(...keys.map((k) => fields[k]), row.id, row.session).run();
  };
  // Після сигналу стеження закінчено. Якщо служба push тимчасово не прийняла — спробуємо наступної хвилини.
  const notify = async (row, data) => {
    let status = 0;
    try {
      status = await sendPush(row, data, vapid);
    } catch {}
    if ((status >= 200 && status < 300) || status === 404 || status === 410) await remove(row);
  };
  const wake = (row, body) => notify(row, { title: "Прокидайтеся!", body, alarm: true, tag: "vidbiy-alarm" });

  for (const row of rows) {
    try {
      const cfg = JSON.parse(row.config);
      if (now - row.created_at > WATCH_MAX_AGE || (cfg.cutoffAt && now >= cfg.cutoffAt)) {
        await remove(row);
        continue;
      }
      // Будильник на час ще не настав.
      if (cfg.scheduled && row.schedule_at && now < row.schedule_at) continue;

      // Правила нічної тривоги — один раз, у час за розкладом.
      if (cfg.scheduled && row.schedule_at && !row.shifted && cfg.nightRules.length && cfg.region.sirenId) {
        let result = null;
        try {
          const ids = [cfg.region.sirenId, ...cfg.region.up];
          const intervals = historyIntervals(await Promise.all(ids.map(history)), now);
          result = evaluateNightRules({ baseAt: row.schedule_at, rules: cfg.nightRules, intervals, tz: cfg.tz });
        } catch {}
        if (result?.skip) {
          await notify(row, { title: "Будильник не дзвонитиме", body: describeShift(cfg.label, result, cfg.tz), tag: "vidbiy-info" });
          continue;
        }
        if (result) {
          cfg.label = formatAt(result.at, cfg.tz);
          await update(row, { schedule_at: result.at, shifted: 1, config: JSON.stringify(cfg), updated_at: now });
          continue;
        }
        await update(row, { shifted: 1, updated_at: now });
      }

      const status = statusFor(cfg.region, sirenAlerts, ubilling);
      if (status === null) {
        // Настав час будильника, а стан невідомий: краще розбудити, ніж проспати (як і на сторінці).
        if (cfg.scheduled && !row.saw_alert) {
          if (!row.failing_since) await update(row, { failing_since: now });
          else if (now - row.failing_since >= MIN) await wake(row, `Будильник о ${cfg.label} (не вдалося перевірити тривогу)`);
        }
        continue;
      }
      if (row.failing_since) await update(row, { failing_since: 0 });

      if (status !== "NONE") {
        if (!row.saw_alert || row.clear_since) await update(row, { saw_alert: 1, clear_since: 0, updated_at: now });
        continue;
      }
      if (row.saw_alert) {
        if (cfg.stableMs) {
          if (!row.clear_since) {
            await update(row, { clear_since: now });
            continue;
          }
          if (now - row.clear_since < cfg.stableMs) continue;
        }
        await wake(row, `Відбій тривоги: ${cfg.region.name}`);
      } else if (cfg.scheduled) {
        await wake(row, `Будильник о ${cfg.label}`);
      }
    } catch {
      // Одне зіпсоване очікування не має зупиняти решту.
    }
  }
}
