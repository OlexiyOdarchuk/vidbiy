// Проксі для веб-версії «Відбою»: siren.pp.ua та ubilling не віддають CORS-заголовки,
// тому браузер не може читати їх напряму. Відповіді кешуються на краю Cloudflare,
// тож скільки б людей не відкрили сайт, до джерел ідуть лічені запити на хвилину.

const ROUTES = {
  "/alerts": { url: "https://siren.pp.ua/api/v3/alerts", ttl: 15 },
  "/regions": { url: "https://siren.pp.ua/api/v3/regions", ttl: 86400 },
  "/ubilling": { url: "https://ubilling.net.ua/aerialalerts/", ttl: 15 },
};

const ALLOWED_ORIGINS = [
  "https://vidbiy.ishawyha.dev",
  "https://olexiyodarchuk.github.io",
  "http://localhost:8000",
  "http://127.0.0.1:8000",
];

function corsHeaders(origin) {
  const allowed = ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0];
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Vary": "Origin",
  };
}

export default {
  async fetch(request) {
    const origin = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }

    const route = ROUTES[new URL(request.url).pathname];
    if (request.method !== "GET" || !route) {
      return new Response("Not found", { status: 404, headers: corsHeaders(origin) });
    }

    let upstream;
    try {
      upstream = await fetch(route.url, {
        headers: { "User-Agent": "Vidbiy-web-proxy" },
        cf: { cacheTtl: route.ttl, cacheEverything: true },
      });
    } catch {
      return new Response("Upstream unavailable", { status: 502, headers: corsHeaders(origin) });
    }

    return new Response(upstream.body, {
      status: upstream.status,
      headers: {
        ...corsHeaders(origin),
        "Content-Type": upstream.headers.get("Content-Type") || "application/json",
        "Cache-Control": `public, max-age=${Math.min(route.ttl, 15)}`,
      },
    });
  },
};
