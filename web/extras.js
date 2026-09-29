// Чиста логіка додаткових можливостей: вебхуки, завдання для вимкнення, статистика.
// Без DOM і мережі, щоб її можна було перевірити окремо.

import { formatDuration, mergeIntervals } from "./nightrule.js";

const MIN = 60_000;
const DAY = 24 * 60 * MIN;
const hhmm = (d) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

// ---------- Вебхуки ----------

export const HOOK_EVENTS = [
  ["armed", "Очікування увімкнено"],
  ["alert", "Почалася тривога"],
  ["clear", "Відбій тривоги"],
  ["ring", "Будильник задзвонив"],
  ["stop", "Будильник вимкнено"],
];
export const EVENT_NAMES = Object.fromEntries([...HOOK_EVENTS, ["test", "Перевірка"]]);

export const DEFAULT_BODY = '{"event":"{event}","place":"{place}","time":"{timestamp}","reason":"{reason}"}';

/**
 * Підставляє {event}, {event_name}, {place}, {time}, {timestamp}, {reason}.
 * У JSON-тілі значення екрануються, щоб лапки в назві місця чи причині не зламали JSON.
 */
export function fillTemplate(template, vars, { json = false } = {}) {
  return String(template).replace(/\{(event_name|event|place|timestamp|time|reason)\}/g, (_, key) => {
    const value = String(vars[key] ?? "");
    return json ? JSON.stringify(value).slice(1, -1) : value;
  });
}

/** Значення підстановок для події в момент at. */
export function hookVars(event, { place = "", reason = "", at = Date.now() } = {}) {
  const d = new Date(at);
  return { event, event_name: EVENT_NAMES[event] ?? event, place, reason, time: hhmm(d), timestamp: d.toISOString() };
}

/** Заголовки з тексту «Ключ: значення» по рядку; рядки без двокрапки пропускаються. */
export function parseHeaders(text) {
  const out = {};
  for (const line of String(text || "").split(/\r?\n/)) {
    const i = line.indexOf(":");
    if (i <= 0) continue;
    const key = line.slice(0, i).trim();
    if (key) out[key] = line.slice(i + 1).trim();
  }
  return out;
}

/** Запит для fetch: { url, init }. Для POST/PUT без свого тіла — JSON за замовчуванням. */
export function buildRequest(hook, vars) {
  const url = fillTemplate(hook.url.trim(), vars);
  const method = hook.method || "POST";
  const headers = {};
  for (const [k, v] of Object.entries(parseHeaders(hook.headers))) headers[k] = fillTemplate(v, vars);
  const init = { method, headers };
  if (method !== "GET") {
    const custom = String(hook.body || "").trim();
    const hasType = Object.keys(headers).some((k) => k.toLowerCase() === "content-type");
    const looksJson = !custom || /^[[{]/.test(custom);
    init.body = fillTemplate(custom || DEFAULT_BODY, vars, { json: looksJson });
    if (!hasType) headers["Content-Type"] = looksJson ? "application/json" : "text/plain";
  }
  return { url, init };
}

/** Браузер зі сторінки https може надсилати лише на https (і на localhost для перевірки). */
export function browserCanSend(url) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || (u.protocol === "http:" && ["localhost", "127.0.0.1"].includes(u.hostname));
  } catch {
    return false;
  }
}

// ---------- Завдання для вимкнення ----------

const rand = (rnd, lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));

/** Приклад заданої складності: { text, answer }. */
export function makeProblem(level, rnd = Math.random) {
  if (level === "HARD") {
    const a = rand(rnd, 12, 49), b = rand(rnd, 3, 9), c = rand(rnd, 11, 99);
    return { text: `${a} × ${b} + ${c}`, answer: a * b + c };
  }
  if (level === "MEDIUM") {
    const a = rand(rnd, 12, 99), b = rand(rnd, 3, 9);
    return { text: `${a} × ${b}`, answer: a * b };
  }
  const a = rand(rnd, 10, 89), b = rand(rnd, 10, 89);
  return { text: `${a} + ${b}`, answer: a + b };
}

/** Відповідь з поля: пробіли й знак мінус із клавіатури не заважають. */
export function checkAnswer(input, answer) {
  const text = String(input).replace(/\s+/g, "").replace("−", "-");
  return /^-?\d+$/.test(text) && Number(text) === answer;
}

/** Прискорення з урахуванням тяжіння, при якому рух вважається струсом (≈ 2.2 g). */
export const SHAKE_THRESHOLD = 2.2 * 9.81;

// ---------- Статистика ----------

/**
 * Ваші ночі за останні days днів з історії (події з полем type; старі події без нього не рахуються).
 * dismissDelay — середні хвилини від сигналу до вимкнення.
 */
export function nightStats(sessions, now, days = 30) {
  const since = now - days * DAY;
  const recent = sessions.filter((s) => s.start >= since);
  const count = (s, ...types) => s.events.filter((e) => types.includes(e.type)).length;
  const delays = [];
  for (const s of recent) {
    let rang = 0;
    for (const e of s.events) {
      if (e.type === "ring" && !rang) rang = e.at;
      if (e.type === "stop" && rang) {
        delays.push(e.at - rang);
        rang = 0;
      }
    }
  }
  return {
    sessions: recent.length,
    alertNights: recent.filter((s) => count(s, "alert", "clear") > 0).length,
    rings: recent.reduce((n, s) => n + count(s, "ring"), 0),
    snoozes: recent.reduce((n, s) => n + count(s, "snooze"), 0),
    shifts: recent.reduce((n, s) => n + count(s, "shift", "skip"), 0),
    dismissDelay: delays.length ? delays.reduce((a, b) => a + b, 0) / delays.length / MIN : null,
  };
}

/** Ніч тривоги: хоч частина між 00:00 і 06:00 місцевого часу. */
function nightPart(a, b) {
  let sum = 0;
  const d = new Date(a);
  for (let day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(); day < b; day += DAY) {
    const n0 = new Date(day).setHours(0, 0, 0, 0);
    const n6 = new Date(day).setHours(6, 0, 0, 0);
    sum += Math.max(0, Math.min(b, n6) - Math.max(a, n0));
  }
  return sum;
}

/**
 * Тривоги в місці (злиті інтервали [start, end]). Дані siren.pp.ua покривають лише останні кілька десятків тривог,
 * тож рахуємо в межах [oldest, now].
 */
export function regionStats(intervals, now) {
  const list = mergeIntervals(intervals.map(([a, b]) => [a, Math.min(b, now)]));
  if (!list.length) return null;
  const week = list.filter(([, b]) => b >= now - 7 * DAY);
  const durations = list.map(([a, b]) => (b - a) / MIN);
  const night = list.filter(([a, b]) => nightPart(a, b) > 0);
  // О котрій найчастіше закінчуються нічні тривоги (лише ті, що вже закінчилися).
  const hours = new Array(24).fill(0);
  for (const [, b] of night) if (b < now) hours[new Date(b).getHours()]++;
  const peak = hours.some(Boolean) ? hours.indexOf(Math.max(...hours)) : null;
  return {
    total: list.length,
    oldest: list[0][0],
    week: week.length,
    avg: durations.reduce((x, y) => x + y, 0) / durations.length,
    longest: Math.max(...durations),
    nightShare: night.length / list.length,
    endHour: peak,
  };
}

/**
 * Хвилини тривоги за кожну з останніх n ночей (18:00 попереднього дня — 09:00), від найстаршої до сьогоднішньої.
 * null — ніч раніше за найстаршу відому тривогу: даних немає.
 */
export function nightBars(intervals, now, n = 14) {
  const list = mergeIntervals(intervals);
  const oldest = list.length ? list[0][0] : now;
  const today = new Date(now);
  const out = [];
  for (let i = n - 1; i >= 0; i--) {
    const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i - 1, 18).getTime();
    const to = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i, 9).getTime();
    if (to < oldest) {
      out.push({ day: to, minutes: null });
      continue;
    }
    let sum = 0;
    for (const [a, b] of list) sum += Math.max(0, Math.min(b, to, now) - Math.max(a, from));
    out.push({ day: to, minutes: Math.round(sum / MIN) });
  }
  return out;
}

export { formatDuration };
