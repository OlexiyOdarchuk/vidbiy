// Правила нічної тривоги: чи переносити будильник на час, якщо вночі була тривога.
// Без DOM і без мережі, бо цей самий модуль використовує Worker для сповіщень (worker/src/index.js).

const MIN = 60_000;

/** Тривалість словами: «45 хв», «2 год», «2 год 10 хв». */
export function formatDuration(minutes) {
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  if (!h) return `${m} хв`;
  return m ? `${h} год ${m} хв` : `${h} год`;
}

const hhmm = (m) => `${String(Math.floor(m / 60) % 24).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

/** Один рядок для списку: «Тривога з 00:00 до 06:00 → о 09:00». */
export function describeRule(rule) {
  const cond = rule.minDuration
    ? `Тривога понад ${formatDuration(rule.minDuration)} з ${hhmm(rule.from)} до ${hhmm(rule.to)}`
    : `Тривога з ${hhmm(rule.from)} до ${hhmm(rule.to)}`;
  const action = rule.action === "SKIP" ? "не будити"
    : rule.action === "LATER" ? `на ${formatDuration(rule.later)} пізніше`
    : `о ${hhmm(rule.at)}`;
  return `${cond} → ${action}`;
}

// ---------- Час у часовому поясі ----------
// Правила задані в місцевому часі користувача, а Worker працює в UTC, тож рахуємо через Intl.

const formatters = new Map();
function partsAt(ms, tz) {
  let f = formatters.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone: tz, hourCycle: "h23",
      year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
    });
    formatters.set(tz, f);
  }
  const p = Object.fromEntries(f.formatToParts(ms).map((x) => [x.type, +x.value]));
  return { y: p.year, m: p.month - 1, d: p.day, minutes: p.hour * 60 + p.minute, s: p.second };
}

/** Зсув місцевого часу від UTC у хвилинах у момент ms. */
function offsetAt(ms, tz) {
  const p = partsAt(ms, tz);
  return Math.round((Date.UTC(p.y, p.m, p.d, 0, p.minutes, p.s) - Math.floor(ms / 1000) * 1000) / MIN);
}

/** Момент (мс) для місцевої дати й часу доби; день може виходити за межі місяця, Date.UTC це вирівнює. */
export function localTime(y, m, d, minutes, tz) {
  const wall = Date.UTC(y, m, d, 0, minutes);
  let t = wall - offsetAt(wall, tz) * MIN;
  const again = offsetAt(t, tz);
  if (wall - again * MIN !== t) t = wall - again * MIN; // поруч із переведенням годинника
  return t;
}

export const localParts = partsAt;

/** Години «HH:MM» для моменту ms у поясі tz. */
export function formatAt(ms, tz) {
  return hhmm(partsAt(ms, tz).minutes);
}

// ---------- Тривоги ----------

/**
 * Інтервали тривог з відповідей siren.pp.ua `alerts/regionHistory` (по одній на місце й кожного предка).
 * Тривога, що ще триває, має isContinue і порожню дату кінця — рахуємо її до [now].
 */
export function historyIntervals(responses, now) {
  const out = [];
  for (const res of responses) {
    for (const region of res || []) {
      for (const a of region.alarms || []) {
        const start = Date.parse(a.startDate);
        let end = a.isContinue ? now : Date.parse(a.endDate);
        if (!Number.isFinite(end) || end < start) end = now;
        if (Number.isFinite(start)) out.push([start, Math.min(end, now)]);
      }
    }
  }
  return mergeIntervals(out);
}

export function mergeIntervals(list) {
  const sorted = list.filter(([a, b]) => b > a).sort((x, y) => x[0] - y[0]);
  const out = [];
  for (const [a, b] of sorted) {
    const last = out.at(-1);
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else out.push([a, b]);
  }
  return out;
}

function overlap(intervals, from, to) {
  let sum = 0;
  for (const [a, b] of intervals) sum += Math.max(0, Math.min(b, to) - Math.max(a, from));
  return sum;
}

/**
 * Що робити з будильником, який мав спрацювати в baseAt.
 * Повертає null (без змін), { skip: true, duration } або { at, duration } — новий час.
 * duration — тривалість тривоги у вікні правила, що спрацювало (хв).
 */
export function evaluateNightRules({ baseAt, rules, intervals, tz }) {
  const base = partsAt(baseAt, tz);
  let result = null;
  for (const rule of rules || []) {
    if (!rule.enabled) continue;
    let start = localTime(base.y, base.m, base.d, rule.from, tz);
    if (start >= baseAt) start = localTime(base.y, base.m, base.d - 1, rule.from, tz);
    const len = ((rule.to - rule.from + 1440) % 1440 || 1440) * MIN;
    const end = Math.min(start + len, baseAt);
    const duration = Math.round(overlap(intervals, start, end) / MIN);
    if (!(duration > 0 && duration >= (rule.minDuration || 0))) continue;

    if (rule.action === "SKIP") return { skip: true, duration };
    const at = rule.action === "LATER"
      ? baseAt + rule.later * MIN
      : localTime(base.y, base.m, base.d, rule.at, tz);
    if (at <= baseAt) continue; // правило не може розбудити раніше
    if (!result || at > result.at) result = { at, duration };
  }
  return result;
}

/** Текст про перенесення: «Будильник о 07:30 перенесено на 09:00: уночі тривога тривала 2 год 10 хв». */
export function describeShift(label, result, tz) {
  const why = `уночі тривога тривала ${formatDuration(result.duration)}`;
  return result.skip
    ? `Будильник о ${label} сьогодні не дзвонитиме: ${why}`
    : `Будильник о ${label} перенесено на ${formatAt(result.at, tz)}: ${why}`;
}
