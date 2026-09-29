import {
  DEFAULT_BODY, HOOK_EVENTS, SHAKE_THRESHOLD, browserCanSend, buildRequest, checkAnswer, formatDuration,
  hookVars, makeProblem, nightBars, nightStats, regionStats,
} from "./extras.js";
import { fillIcons, icon } from "./icons.js";
import { describeRule, describeShift, evaluateNightRules, historyIntervals } from "./nightrule.js";
import { DEFAULT_REGION, PROXY, ancestors, descendants, getJSON, getTree, mountPicker } from "./regions.js";
import { DEFAULT_SOUND, mountSoundList, soundName, soundUrl, stopPreview } from "./sounds.js";

const POLL_MS = 20_000;
const SNOOZE_MS = 5 * 60_000;
const NO_CONNECTION_MS = 5 * 60_000;
const NIGHT_AFTER_MS = 30_000;
const STALE_MS = 90_000;
const MIN = 60_000;
const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone;

const $ = (id) => document.getElementById(id);
const hhmm = (d) => d.toLocaleTimeString("uk-UA", { hour: "2-digit", minute: "2-digit" });
const fmtMinutes = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const parseTime = (v) => { const [h, m] = v.split(":").map(Number); return h * 60 + m; };
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
/** 1 правило, 2 правила, 5 правил. */
const plural = (n, one, few, many) =>
  n % 10 === 1 && n % 100 !== 11 ? one : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? few : many;

const WEEKDAYS = 0b0011111;
const EVERY_DAY = 0b1111111;
const WEEKEND = 0b1100000;
const SHORT_DAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "нд"];
const IN_DAY = ["у понеділок", "у вівторок", "у середу", "у четвер", "у п'ятницю", "у суботу", "у неділю"];

// ---------- Налаштування ----------

const SETTINGS_KEY = "vidbiy.settings";
const WATCH_KEY = "vidbiy.watch";
const HISTORY_KEY = "vidbiy.history";

function load(key, fallback) {
  try {
    return { ...fallback, ...JSON.parse(localStorage.getItem(key)) };
  } catch {
    return fallback;
  }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch {}
}

const newAlarm = (id) => ({ id, enabled: true, minutes: 7 * 60 + 30, days: WEEKDAYS, nightRule: false, place: null });

// Нові можливості вимкнені, доки їх не ввімкнуть; лише перевірка перед сном одразу діє, бо тільки попереджає.
const settings = load(SETTINGS_KEY, {
  region: DEFAULT_REGION,
  cutoff: -1,
  alarmOnNoConnection: true,
  onboarded: false,
  sound: DEFAULT_SOUND,
  customName: "",
  // Будильники на час: дні бітами, біт 0 — понеділок … біт 6 — неділя; 0 — один раз.
  // place: null — основне місце (settings.region).
  alarms: [{ ...newAlarm(1), enabled: false }],
  nightRules: [],
  stableClear: 0, // хв, скільки має протриматися відбій; 0 — будити одразу
  bedtimeCheck: true,
  alertStartNotice: false,
  sunrise: 0, // хв світанку перед будильником на час; 0 — вимкнено
  push: false,
  dismissTask: "NONE", // NONE | MATH | SHAKE
  mathLevel: "EASY", // EASY | MEDIUM | HARD
  mathCount: 1,
  shakeCount: 20,
  voice: false,
  webhooks: [],
});
const saveSettings = () => save(SETTINGS_KEY, settings);

// До версії 1.5 будильник на час був один — у settings.schedule.
if (settings.schedule) {
  settings.alarms = [{ ...newAlarm(1), ...settings.schedule }];
  delete settings.schedule;
  saveSettings();
}

// ---------- Будильники на час ----------

function nextAlarmAt(alarm, now = new Date()) {
  const { minutes, days } = alarm;
  for (let i = 0; i <= 7; i++) {
    const t = new Date(now);
    t.setDate(t.getDate() + i);
    t.setHours(Math.floor(minutes / 60), minutes % 60, 0, 0);
    if (t <= now) continue;
    const dow = (t.getDay() + 6) % 7; // понеділок = 0
    if (days === 0 || days & (1 << dow)) return t.getTime();
  }
  return 0;
}

/** Найближчий увімкнений будильник: { at, alarm } або null. */
function nextSchedule(now = new Date()) {
  let best = null;
  for (const alarm of settings.alarms) {
    if (!alarm.enabled) continue;
    const at = nextAlarmAt(alarm, now);
    if (at && (!best || at < best.at)) best = { at, alarm };
  }
  return best;
}

const findAlarm = (id) => settings.alarms.find((a) => a.id === id);
const alarmRegion = (alarm) => alarm?.place || settings.region;

function describeDays(days) {
  if (days === 0) return "Один раз";
  if (days === EVERY_DAY) return "Щодня";
  if (days === WEEKDAYS) return "Будні";
  if (days === WEEKEND) return "Вихідні";
  return SHORT_DAYS.filter((_, i) => days & (1 << i)).join(", ");
}

/** «сьогодні о 07:30», «завтра о 07:30», «у понеділок о 07:30». */
function describeWhen(at) {
  const d = new Date(at);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((new Date(d).setHours(0, 0, 0, 0) - today) / 86_400_000);
  const day = diff === 0 ? "сьогодні" : diff === 1 ? "завтра" : IN_DAY[(d.getDay() + 6) % 7];
  return `${day} о ${hhmm(d)}`;
}

function nextOccurrence(minutes) {
  const t = new Date();
  t.setHours(Math.floor(minutes / 60), minutes % 60, 0, 0);
  if (t <= new Date()) t.setDate(t.getDate() + 1);
  return t.getTime();
}

// ---------- Історія ночей ----------

const HISTORY_MAX = 30;

function loadHistory() {
  try {
    const list = JSON.parse(localStorage.getItem(HISTORY_KEY));
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

/**
 * Подія в поточному сеансі; { start: true } відкриває новий сеанс (ніч).
 * type — для статистики: armed, scheduled, alert, clear, ring, snooze, stop, cutoff, shift, skip, offline.
 */
function logEvent(text, { start = false, type = "" } = {}) {
  const list = loadHistory();
  let session = list.at(-1);
  if (start || !session || session.end) {
    session = { start: Date.now(), end: 0, events: [] };
    list.push(session);
  }
  session.events.push(type ? { at: Date.now(), text, type } : { at: Date.now(), text });
  save(HISTORY_KEY, list.slice(-HISTORY_MAX));
}

function endSession() {
  const list = loadHistory();
  const session = list.at(-1);
  if (session && !session.end) {
    session.end = Date.now();
    save(HISTORY_KEY, list);
  }
}

// ---------- Стан очікування ----------

const ACTIVE = ["SCHEDULED", "WAITING_ALERT", "ALERT", "CLEARING", "SNOOZED"];
const CHECKING = ["WAITING_ALERT", "ALERT", "CLEARING"];

const watch = {
  phase: "IDLE", // IDLE | SCHEDULED | WAITING_ALERT | ALERT | CLEARING | RINGING | SNOOZED
  scheduleAt: 0, // коли спрацює будильник на час (у фазі SCHEDULED), після перенесення — новий час
  baseAt: 0, // коли будильник мав спрацювати за розкладом
  alarmId: 0,
  shifted: false, // правила нічної тривоги вже застосовано
  shiftText: "",
  evaluating: false,
  scheduleRun: false, // очікування запущене будильником на час
  scheduleLabel: "",
  region: null, // місце, за яким стежимо зараз
  runStartedAt: 0,
  text: "",
  reason: "",
  source: "",
  checkedAt: 0,
  sawAlert: false,
  lastStatus: null, // NONE | ALERT — щоб помітити саме початок тривоги
  clearSince: 0, // коли настав відбій (для «Чекати, щоб відбій утримався»)
  noConnLogged: false,
  lastOk: 0,
  lastTry: 0,
  cutoffAt: 0,
  timer: 0,
  generation: 0,
  sessionId: 0,
  test: false,
};

// Якщо сторінку закрили чи оновили під час очікування, запропонуємо продовжити.
let interrupted = load(WATCH_KEY, { armed: false }).armed ? load(WATCH_KEY, {}) : null;
function persistWatch() {
  const scheduled = watch.phase === "SCHEDULED";
  save(WATCH_KEY, {
    armed: watch.phase !== "IDLE" && !watch.test,
    sawAlert: watch.sawAlert,
    cutoffAt: watch.cutoffAt,
    scheduleAt: scheduled ? watch.scheduleAt : 0,
    baseAt: scheduled ? watch.baseAt : 0,
    alarmId: watch.alarmId,
    shifted: scheduled && watch.shifted,
    shiftText: scheduled ? watch.shiftText : "",
    region: watch.region,
    sessionId: watch.sessionId,
  });
  syncPush();
}

// ---------- Дані про тривоги ----------

async function fetchStatus(region) {
  let firstError = null;
  if (region.sirenId) {
    try {
      const [places, alerts] = await Promise.all([getTree(), getJSON("/alerts")]);
      const up = new Set(ancestors(places, region.sirenId));
      const down = descendants(places, region.sirenId);
      let partial = false;
      for (const a of alerts) {
        if (!a.activeAlerts?.length) continue;
        if (a.regionId === region.sirenId || up.has(a.regionId)) return { status: "ACTIVE", source: "siren.pp.ua" };
        if (down.has(a.regionId)) partial = true;
      }
      return { status: partial ? "PARTIAL" : "NONE", source: "siren.pp.ua" };
    } catch {
      firstError ??= "siren.pp.ua: немає зв'язку";
    }
  }
  if (region.ubilling) {
    try {
      const state = (await getJSON("/ubilling")).states?.[region.ubilling];
      if (typeof state?.alertnow !== "boolean") throw new Error("format");
      return { status: state.alertnow ? "ACTIVE" : "NONE", source: "ubilling.net.ua" };
    } catch {
      firstError ??= "ubilling.net.ua: немає зв'язку";
    }
  }
  throw new Error(firstError ?? "Немає доступних джерел даних");
}

// ---------- Звук і екран ----------

const sound = $("sound");

async function applySound() {
  sound.src = await soundUrl(settings.sound);
  sound.load();
}

// Якщо обраний файл раптом не грає, звучить стандартний сигнал: будильник не має мовчати.
function playAlarm() {
  sound.currentTime = 0;
  sound.play().catch(() => {
    sound.src = `sounds/${DEFAULT_SOUND}.mp3`;
    sound.play().catch(() => {});
  });
}
let wakeLock = null;
let noSleep = null;

// iOS дозволяє програвати звук лише після дотику, тому «розблоковуємо» його в момент увімкнення.
// Якщо за цей час уже почався справжній сигнал, його не чіпаємо.
async function unlockAudio() {
  try {
    sound.muted = true;
    await sound.play();
    if (watch.phase !== "RINGING") {
      sound.pause();
      sound.currentTime = 0;
    }
  } catch {}
  sound.muted = false;
  // Голос на iPhone теж запрацює вночі, лише якщо перше мовлення почалося з дотику.
  if (settings.voice && window.speechSynthesis) {
    const u = new SpeechSynthesisUtterance("");
    u.volume = 0;
    speechSynthesis.speak(u);
  }
}

async function keepScreenOn() {
  try {
    if ("wakeLock" in navigator && !wakeLock) {
      wakeLock = await navigator.wakeLock.request("screen");
      wakeLock.addEventListener("release", () => { wakeLock = null; });
    }
  } catch {}
  // Запасний спосіб для iOS, де Wake Lock у програмах з початкового екрана довго не працював:
  // беззвучне відео, що грає по колу, не дає екрану згаснути.
  if (!noSleep) {
    noSleep = document.createElement("video");
    Object.assign(noSleep, { src: "nosleep.mp4", muted: true, loop: true, playsInline: true });
    noSleep.setAttribute("playsinline", "");
    noSleep.style.cssText = "position:fixed;width:1px;height:1px;opacity:0;pointer-events:none";
    document.body.append(noSleep);
  }
  noSleep.play().catch(() => {});
}

function releaseScreen() {
  wakeLock?.release().catch(() => {});
  wakeLock = null;
  noSleep?.pause();
}

// ---------- Керування будильником ----------

async function arm(resume = null) {
  const unlocked = unlockAudio();
  keepScreenOn();
  clearTimeout(watch.timer);
  // Після перезавантаження сторінки продовжуємо чекати той самий час; якщо він уже минув,
  // scheduleTick одразу запустить перевірку «за розкладом».
  const next = resume ? null : nextSchedule();
  const scheduleAt = resume ? resume.scheduleAt || 0 : next?.at || 0;
  const alarm = resume ? findAlarm(resume.alarmId) : next?.alarm;
  const common = {
    reason: "",
    source: "",
    checkedAt: 0,
    sawAlert: !!resume?.sawAlert,
    lastStatus: null,
    clearSince: 0,
    noConnLogged: false,
    evaluating: false,
    generation: watch.generation + 1,
    sessionId: resume?.sessionId || Date.now(),
    test: false,
  };
  if (resume?.sessionId) logEvent("Продовжено після перезавантаження сторінки");

  if (scheduleAt) {
    Object.assign(watch, common, {
      phase: "SCHEDULED",
      text: resume?.shiftText || "",
      scheduleAt,
      baseAt: resume?.baseAt || scheduleAt,
      alarmId: alarm?.id ?? resume?.alarmId ?? 0,
      shifted: !!resume?.shifted,
      shiftText: resume?.shiftText || "",
      region: resume?.region || alarmRegion(alarm),
      scheduleRun: false,
      sawAlert: false,
    });
    if (!resume?.sessionId) {
      logEvent(`Увімкнено: будильник о ${hhmm(new Date(scheduleAt))}`, { start: true, type: "armed" });
      fireHooks("armed");
    }
    interrupted = null;
    persistWatch();
    render();
    bumpNight();
    await unlocked;
    scheduleTick();
    return;
  }
  Object.assign(watch, common, {
    scheduleRun: false,
    alarmId: 0,
    phase: resume?.sawAlert ? "ALERT" : "WAITING_ALERT",
    text: "Перевірка стану тривоги…",
    region: resume?.region || settings.region,
    lastOk: Date.now(),
    lastTry: 0,
    cutoffAt: resume?.cutoffAt || (settings.cutoff >= 0 ? nextOccurrence(settings.cutoff) : 0),
  });
  if (!resume?.sessionId) {
    logEvent("Увімкнено: чекати відбою", { start: true, type: "armed" });
    fireHooks("armed");
  }
  interrupted = null;
  persistWatch();
  render();
  bumpNight();
  await unlocked;
  tick();
}

// Таймери у браузері можуть відставати, тому не чекаємо одним setTimeout, а звіряємо годинник.
function scheduleTick() {
  clearTimeout(watch.timer);
  if (watch.phase !== "SCHEDULED" || watch.evaluating) return;
  if (Date.now() >= watch.scheduleAt) {
    startScheduledRun();
    return;
  }
  // Світанок: за кілька хвилин до будильника показуємо нічний екран, що поступово світлішає.
  if (sunriseProgress() > 0 && $("night").hidden && $("alarm").hidden) showNight();
  render();
  watch.timer = setTimeout(scheduleTick, Math.min(15_000, Math.max(500, watch.scheduleAt - Date.now())));
}

/** Тривоги за ніч у місці та в усьому, що його охоплює (область, район). */
async function nightIntervals(region) {
  let ids = [region.sirenId];
  try { ids = [region.sirenId, ...ancestors(await getTree(), region.sirenId)]; } catch {}
  const responses = await Promise.all(ids.map((id) => getJSON(`/history?regionId=${id}`)));
  return historyIntervals(responses, Date.now());
}

async function startScheduledRun() {
  const gen = watch.generation;
  const alarm = findAlarm(watch.alarmId);
  const label = hhmm(new Date(watch.baseAt || watch.scheduleAt));
  // Одноразові будильники на цю хвилину вимикаються, щойно спрацювали.
  if (!watch.shifted) {
    for (const a of settings.alarms) {
      if (a.enabled && a.days === 0 && nextAlarmAt(a, new Date(watch.baseAt - 1)) === watch.baseAt) a.enabled = false;
    }
    saveSettings();
  }
  const region = watch.region || alarmRegion(alarm);

  // Правила нічної тривоги перевіряємо один раз, у час за розкладом. Без історії (немає зв'язку,
  // джерело без історії) будимо як звичайно: краще не проспати.
  if (!watch.shifted && alarm?.nightRule && region.sirenId && settings.nightRules.some((r) => r.enabled)) {
    watch.evaluating = true;
    watch.text = `Будильник о ${label}: перевірка нічної тривоги…`;
    render();
    let result = null;
    try {
      const intervals = await nightIntervals(region);
      result = evaluateNightRules({ baseAt: watch.scheduleAt, rules: settings.nightRules, intervals, tz: TZ });
    } catch {}
    watch.evaluating = false;
    if (gen !== watch.generation) return;
    if (result) {
      const text = describeShift(label, result, TZ);
      if (result.skip) {
        finish(text, "skip");
        return;
      }
      logEvent(text, { type: "shift" });
      Object.assign(watch, { scheduleAt: result.at, shifted: true, shiftText: text, text });
      persistWatch();
      scheduleTick();
      return;
    }
  }

  watch.scheduleLabel = hhmm(new Date(watch.scheduleAt));
  logEvent(`Будильник о ${watch.scheduleLabel}: перевірка тривоги`, { type: "scheduled" });
  Object.assign(watch, {
    phase: "WAITING_ALERT",
    scheduleRun: true,
    runStartedAt: Date.now(),
    region,
    text: `Будильник о ${watch.scheduleLabel}: перевірка тривоги…`,
    lastOk: Date.now(),
    lastTry: 0,
    cutoffAt: settings.cutoff >= 0 ? nextOccurrence(settings.cutoff) : 0,
  });
  persistWatch();
  render();
  tick();
}

async function tick() {
  const gen = watch.generation;
  clearTimeout(watch.timer);
  if (!CHECKING.includes(watch.phase)) return;

  if (watch.cutoffAt && Date.now() >= watch.cutoffAt) {
    finish(`Настав час ${hhmm(new Date(watch.cutoffAt))}, будильник вимкнено без сигналу`, "cutoff");
    return;
  }

  // Поки сторінка була заморожена (згорнута чи телефон заснув), перевірок не було:
  // цей час не рахуємо як відсутність зв'язку, інакше перша ж невдала спроба одразу будить.
  if (watch.lastTry && Date.now() - watch.lastTry > POLL_MS * 3) watch.lastOk = Date.now();
  watch.lastTry = Date.now();

  const region = watch.region || settings.region;
  try {
    const r = await fetchStatus(region);
    if (gen !== watch.generation) return;
    watch.lastOk = Date.now();
    watch.source = r.source;
    watch.checkedAt = Date.now();
    if (r.status !== "NONE") {
      // Саме початок тривоги (була тиша), а не тривога, що вже йшла на момент увімкнення.
      if (watch.lastStatus === "NONE") {
        logEvent("Почалася тривога", { type: "alert" });
        if (settings.alertStartNotice) navigator.vibrate?.([300, 200, 300]);
        fireHooks("alert");
      } else if (watch.lastStatus === null) {
        logEvent("Триває тривога", { type: "alert" });
        fireHooks("alert");
      }
      watch.lastStatus = "ALERT";
      watch.sawAlert = true;
      watch.clearSince = 0;
      watch.phase = "ALERT";
      const lead = watch.scheduleRun ? `Будильник о ${watch.scheduleLabel} чекає: ` : "";
      watch.text = r.status === "PARTIAL"
        ? `${lead}${lead ? "т" : "Т"}ривога в частині регіону. ${lead ? "Розбудить" : "Будильник пролунає"} після відбою в усьому регіоні.`
        : lead ? `${lead}триває тривога. Розбудить після відбою.` : "Будильник пролунає після відбою.";
      persistWatch();
    } else if (watch.sawAlert) {
      watch.lastStatus = "NONE";
      const stable = settings.stableClear * MIN;
      if (!watch.clearSince) {
        watch.clearSince = Date.now();
        logEvent("Відбій тривоги", { type: "clear" });
        fireHooks("clear");
      }
      if (!stable) {
        ring(`Відбій тривоги: ${region.name}`);
        return;
      }
      if (Date.now() - watch.clearSince >= stable) {
        ring(`Відбій тривоги: ${region.name}`);
        return;
      }
      watch.phase = "CLEARING";
      watch.text = `Відбій о ${hhmm(new Date(watch.clearSince))}. Розбудить о ${hhmm(new Date(watch.clearSince + stable))}, якщо тривога не повториться.`;
    } else if (watch.scheduleRun) {
      // Настав час будильника, а тривоги немає — будимо, як звичайний будильник.
      ring(`Будильник о ${watch.scheduleLabel}`);
      return;
    } else {
      watch.lastStatus = "NONE";
      watch.phase = "WAITING_ALERT";
      watch.text = "Зараз тривоги немає. Будильник пролунає після відбою наступної тривоги.";
    }
  } catch (e) {
    if (gen !== watch.generation) return;
    if (!watch.noConnLogged) {
      watch.noConnLogged = true;
      logEvent("Немає зв'язку", { type: "offline" });
    }
    // Не знаємо, чи є тривога, — краще розбудити, ніж проспати.
    if (watch.scheduleRun && !watch.sawAlert && Date.now() - watch.runStartedAt >= 60_000) {
      ring(`Будильник о ${watch.scheduleLabel} (не вдалося перевірити тривогу)`);
      return;
    }
    if (settings.alarmOnNoConnection && Date.now() - watch.lastOk >= NO_CONNECTION_MS) {
      ring("Понад 5 хв немає зв'язку з жодним джерелом даних про тривоги");
      return;
    }
    watch.text = `${e.message}. Повторна спроба…`;
  }
  render();
  watch.timer = setTimeout(tick, POLL_MS);
}

function ring(reason, { test = false } = {}) {
  clearTimeout(watch.timer);
  watch.phase = "RINGING";
  watch.reason = reason;
  watch.test = test || watch.test;
  if (test) watch.generation++;
  if (!watch.test) {
    logEvent(`Сигнал: ${reason}`, { type: "ring" });
    syncPush();
    fireHooks("ring", reason);
  }
  keepScreenOn();
  playAlarm();
  startVoice(reason);
  navigator.vibrate?.([800, 600, 800, 600, 800]);
  hideNight();
  $("alarm-reason").textContent = reason;
  $("alarm").hidden = false;
  render();
}

function snooze() {
  sound.pause();
  $("alarm").hidden = true;
  const at = new Date(Date.now() + SNOOZE_MS);
  watch.phase = "SNOOZED";
  watch.text = `Повторний сигнал о ${hhmm(at)}`;
  logEvent(`Відкладено до ${hhmm(at)}`, { type: "snooze" });
  stopVoice();
  closeTask();
  const gen = watch.generation;
  const reason = watch.reason;
  watch.timer = setTimeout(() => gen === watch.generation && ring(reason), SNOOZE_MS);
  render();
  bumpNight();
}

/** type — для історії: stop (вимкнули), cutoff, skip. */
function finish(message = "", type = "stop") {
  clearTimeout(watch.timer);
  sound.pause();
  releaseScreen();
  stopVoice();
  closeTask();
  if (!watch.test && watch.phase !== "IDLE") {
    // «Будильник вимкнено» — лише коли вимкнули сигнал, а не скасували очікування.
    if (!message && ["RINGING", "SNOOZED"].includes(watch.phase)) fireHooks("stop", watch.reason);
    logEvent(message || "Вимкнено", { type });
    endSession();
  }
  Object.assign(watch, {
    phase: "IDLE", text: message, reason: "", source: "", sawAlert: false, test: false, scheduleRun: false, scheduleAt: 0,
    baseAt: 0, alarmId: 0, shifted: false, shiftText: "", evaluating: false, clearSince: 0, region: null,
  });
  watch.generation++;
  persistWatch();
  $("alarm").hidden = true;
  hideNight();
  render();
  if (message) toast(message);
}

// ---------- Перевірка перед сном ----------

let battery = null;
navigator.getBattery?.().then((b) => {
  battery = b;
  b.addEventListener("levelchange", () => render());
  b.addEventListener("chargingchange", () => render());
}).catch(() => {});
addEventListener("online", () => render());
addEventListener("offline", () => render());

function bedtimeIssues() {
  const out = [];
  if (battery && !battery.charging && battery.level <= 0.2) {
    out.push(`Заряд ${Math.round(battery.level * 100)} %. Поставте телефон на зарядку`);
  }
  if (!navigator.onLine) out.push("Немає інтернету — будильник не дізнається про відбій");
  return out;
}

// Дотик до місяця: перед увімкненням попереджаємо про те, через що будильник може не спрацювати.
// Діалог відкривається одразу, без очікувань, щоб дотик «Увімкнути» лишався жестом користувача (звук на iOS).
function armWithCheck() {
  const issues = settings.bedtimeCheck ? bedtimeIssues() : [];
  if (!issues.length) {
    arm(interrupted);
    return;
  }
  $("bedtime-dialog-list").innerHTML = issues.map((t) => `<li>${esc(t)}</li>`).join("");
  $("bedtime-dialog").returnValue = "";
  $("bedtime-dialog").showModal();
}
$("bedtime-dialog").addEventListener("close", () => {
  if ($("bedtime-dialog").returnValue === "arm" && watch.phase === "IDLE") arm(interrupted);
});

// ---------- Вебхуки ----------

function fetchWithTimeout(url, init) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10_000);
  return fetch(url, { ...init, signal: ctrl.signal }).finally(() => clearTimeout(timer));
}

/** Надсилає один вебхук і запам'ятовує результат; помилки не впливають на будильник. */
async function sendHook(hook, vars) {
  const stamp = hhmm(new Date());
  const { url, init } = buildRequest(hook, vars);
  let result;
  if (!browserCanSend(url)) {
    result = `Не надіслано о ${stamp}: сайт може надсилати лише на адреси https`;
  } else {
    try {
      const res = await fetchWithTimeout(url, init);
      result = res.ok ? `Надіслано о ${stamp} · ${res.status}` : `Помилка о ${stamp}: сервер відповів ${res.status}`;
    } catch {
      // Сервер без CORS: браузер не покаже відповідь, але сам запит може дійти. PUT так надіслати не можна.
      try {
        if (init.method === "PUT") throw new Error("cors");
        await fetchWithTimeout(url, { ...init, mode: "no-cors" });
        result = `Надіслано о ${stamp} (відповідь недоступна)`;
      } catch {
        result = `Помилка о ${stamp}: немає зв'язку`;
      }
    }
  }
  hook.last = result;
  saveSettings();
  if (!$("hook-edit").hidden && editingHook === hook.id) renderHookEdit();
  return result;
}

function fireHooks(event, reason = "") {
  const hooks = settings.webhooks.filter((h) => h.enabled && h.url && h.events?.includes(event));
  if (!hooks.length) return;
  const vars = hookVars(event, { place: (watch.region || settings.region).name, reason });
  for (const hook of hooks) sendHook(hook, vars);
}

// ---------- Голос ----------

const synth = window.speechSynthesis;
let ukVoice = null;
let voiceTimer = 0;

function pickVoice() {
  ukVoice = synth?.getVoices().find((v) => v.lang?.toLowerCase().replace("_", "-").startsWith("uk")) || null;
  if (!$("settings").hidden) render();
}
synth?.addEventListener?.("voiceschanged", pickVoice);
pickVoice();

/** Мелодію притишуємо, поки звучить голос (на iPhone гучність сторінці недоступна — там просто поверх). */
function speak(text) {
  if (!synth || !ukVoice) return;
  const u = new SpeechSynthesisUtterance(text);
  u.voice = ukVoice;
  u.lang = ukVoice.lang;
  u.onstart = () => { sound.volume = 0.25; };
  u.onend = u.onerror = () => { sound.volume = 1; };
  synth.cancel();
  synth.speak(u);
}

function startVoice(reason) {
  stopVoice();
  if (!settings.voice || !ukVoice) return;
  const say = () => {
    if (watch.phase !== "RINGING") return;
    speak(`${reason}. Зараз ${hhmm(new Date())}.`);
    voiceTimer = setTimeout(say, 30_000);
  };
  voiceTimer = setTimeout(say, 3_000);
}

function stopVoice() {
  clearTimeout(voiceTimer);
  synth?.cancel();
  sound.volume = 1;
}

// ---------- Завдання для вимкнення ----------

let task = null;

/** «Вимкнути»: одразу або через приклад чи струшування. */
function dismiss() {
  if (settings.dismissTask === "SHAKE") startShake();
  else if (settings.dismissTask === "MATH") startMath();
  else finish();
}

function startMath() {
  closeTask();
  task = { kind: "MATH", done: 0, total: settings.mathCount, problem: makeProblem(settings.mathLevel) };
  $("task-wrong").hidden = true;
  $("task-answer").value = "";
  renderTask();
  $("task-answer").focus();
}

// Датчик руху на iPhone потребує дозволу, і запитати його можна лише в обробнику дотику.
async function startShake() {
  const Motion = window.DeviceMotionEvent;
  if (!Motion) {
    startMath();
    return;
  }
  if (typeof Motion.requestPermission === "function") {
    try {
      if (await Motion.requestPermission() !== "granted") throw new Error("denied");
    } catch {
      toast("Немає доступу до датчика руху, розв'яжіть приклад");
      startMath();
      return;
    }
  }
  closeTask();
  task = { kind: "SHAKE", done: 0, total: settings.shakeCount, last: 0, seen: false };
  addEventListener("devicemotion", onMotion);
  // На комп'ютері подія є, але датчика немає — тоді приклад.
  task.fallback = setTimeout(() => {
    if (task?.kind === "SHAKE" && !task.seen) {
      toast("Датчик руху недоступний, розв'яжіть приклад");
      startMath();
    }
  }, 3_000);
  renderTask();
}

function onMotion(e) {
  const a = e.accelerationIncludingGravity;
  if (!task || task.kind !== "SHAKE" || a?.x == null) return;
  task.seen = true;
  if (Math.hypot(a.x, a.y, a.z) < SHAKE_THRESHOLD || Date.now() - task.last < 250) return;
  task.last = Date.now();
  task.done++;
  navigator.vibrate?.(40);
  if (task.done >= task.total) finish();
  else renderTask();
}

function closeTask() {
  if (task?.fallback) clearTimeout(task.fallback);
  removeEventListener("devicemotion", onMotion);
  task = null;
  renderTask();
}

function renderTask() {
  $("alarm-task").hidden = !task;
  $("alarm").classList.toggle("tasking", !!task);
  $("alarm-stop").hidden = !!task;
  $("task-math").hidden = task?.kind !== "MATH";
  $("task-shake").hidden = task?.kind !== "SHAKE";
  if (task?.kind === "MATH") {
    $("task-problem").textContent = `${task.problem.text} = ?`;
    $("task-math-progress").textContent = task.total > 1 ? `Приклад ${task.done + 1} з ${task.total}` : "Розв'яжіть, щоб вимкнути";
  } else if (task?.kind === "SHAKE") {
    $("task-shake-count").textContent = `${task.done} з ${task.total}`;
    $("task-shake-bar").style.width = `${Math.round((task.done / task.total) * 100)}%`;
  }
}

$("task-form").addEventListener("submit", (e) => {
  e.preventDefault();
  if (task?.kind !== "MATH") return;
  const ok = checkAnswer($("task-answer").value, task.problem.answer);
  $("task-wrong").hidden = ok;
  if (ok) task.done++;
  if (task.done >= task.total) {
    finish();
    return;
  }
  task.problem = makeProblem(settings.mathLevel);
  $("task-answer").value = "";
  renderTask();
  $("task-answer").focus();
});

// ---------- Нічний режим ----------

let nightTimer = 0;
let nightClock = 0;

function bumpNight() {
  clearTimeout(nightTimer);
  if (ACTIVE.includes(watch.phase)) {
    nightTimer = setTimeout(showNight, NIGHT_AFTER_MS);
  }
}

function showNight() {
  if (!ACTIVE.includes(watch.phase) || !$("alarm").hidden) return;
  $("night").hidden = false;
  updateNight();
  clearInterval(nightClock);
  nightClock = setInterval(updateNight, 1000);
}

function hideNight() {
  $("night").hidden = true;
  clearInterval(nightClock);
}

/** 0…1: наскільки «розвиднілося» перед будильником на час; 0 — світанок ще не почався чи вимкнений. */
function sunriseProgress() {
  if (watch.phase !== "SCHEDULED" || !settings.sunrise || watch.evaluating) return 0;
  const left = watch.scheduleAt - Date.now();
  const span = settings.sunrise * MIN;
  return left > span ? 0 : Math.min(1, 1 - left / span);
}

function updateNight() {
  const now = new Date();
  $("night-clock").textContent = hhmm(now);
  const status = {
    SCHEDULED: `Будильник о ${hhmm(new Date(watch.scheduleAt))}`,
    WAITING_ALERT: "Чекаю на тривогу",
    ALERT: "Триває тривога",
    CLEARING: "Відбій, чекаю, чи не повториться тривога",
    SNOOZED: "Будильник відкладено",
  }[watch.phase] ?? "";
  $("night-status").textContent = `${status} · ${(watch.region || settings.region).name}`;
  // Світанок: від чорного до теплого світла, текст темнішає, щоб лишатися читабельним.
  const p = sunriseProgress();
  const night = $("night");
  night.style.backgroundColor = p ? `color-mix(in srgb, #ffb877 ${Math.round(p * p * 100)}%, #000)` : "";
  night.classList.toggle("dawn", p > 0.55);
  // Трохи зсуваємо текст щохвилини, щоб не вигорав екран.
  if (now.getSeconds() === 0) {
    night.firstElementChild.style.transform =
      `translate(${Math.round(Math.random() * 60 - 30)}px, ${Math.round(Math.random() * 120 - 60)}px)`;
  }
}

$("night").addEventListener("click", () => { hideNight(); bumpNight(); });
document.addEventListener("pointerdown", () => bumpNight(), { passive: true });

// Сторінку згорнули: iOS її зупиняє, тож повідомимо про прогалину, коли користувач повернеться.
let hiddenAt = 0;
document.addEventListener("visibilitychange", () => {
  const active = watch.phase !== "IDLE";
  if (document.hidden) {
    if (active) hiddenAt = Date.now();
    return;
  }
  if (!active) return;
  keepScreenOn();
  const gone = hiddenAt ? Date.now() - hiddenAt : 0;
  hiddenAt = 0;
  if (gone > 30_000) {
    toast(`Сторінка була згорнута ${Math.max(1, Math.round(gone / 60_000))} хв, у цей час будильник не стежив за тривогою`);
  }
  if (CHECKING.includes(watch.phase)) tick();
  if (watch.phase === "SCHEDULED") scheduleTick();
});

// ---------- Інтерфейс ----------

const ORB_ICONS = {
  IDLE: "bedtime", SCHEDULED: "alarm", WAITING_ALERT: "radar", ALERT: "campaign", CLEARING: "shield", RINGING: "alarm", SNOOZED: "snooze",
};

function setupOrb(el) {
  el.innerHTML = `<span class="ring"></span><span class="ring"></span><span class="ring"></span><span class="core"></span>`;
  setOrbPhase(el, el.dataset.phase);
}
function setOrbPhase(el, phase) {
  if (el.dataset.phase === phase && el.querySelector(".core svg")) return;
  el.dataset.phase = phase;
  el.querySelector(".core").innerHTML = icon(ORB_ICONS[phase]);
}

const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
const isAndroid = /Android/i.test(navigator.userAgent);
const isStandalone = () => navigator.standalone === true || matchMedia("(display-mode: standalone)").matches;
const finePointer = matchMedia("(pointer: fine)").matches;
const TAP = finePointer ? "Натисніть" : "Торкніться";

const APK_URL = "https://github.com/OlexiyOdarchuk/vidbiy/releases/latest";
const HOWTO = isIOS
  ? {
      title: "Як це працює на iPhone",
      lead: "iPhone не дозволяє сайтам працювати у фоні, тому будильник працює, поки сторінка відкрита.",
      cards: [
        ["add", "Додайте на початковий екран", `У Safari натисніть <span class="inline-icon">${icon("share")}</span> «Поділитися» → «На початковий екран». Сайт відкриватиметься як окрема програма.`, "install-card"],
        ["charge", "Поставте телефон на зарядку", "Залиште програму відкритою. Екран не гаснутиме, а сторінка затемниться майже до чорного."],
        ["volume", "Увімкніть гучність", "Сигнал грає навіть у беззвучному режимі, але гучність береться з кнопок гучності. Додайте її перед сном."],
      ],
    }
  : isAndroid
    ? {
        title: "Як це працює",
        lead: "Сайт стежить за тривогою, поки сторінка відкрита. Для Android є програма, яка працює у фоні.",
        cards: [
          ["android", "Встановіть програму для Android", `Вона надійніша за сайт: будильник спрацює навіть на заблокованому телефоні. <a href="${APK_URL}" target="_blank" rel="noopener">Завантажити APK</a>`],
          ["charge", "Або залиште сайт відкритим", "Поставте телефон на зарядку й не закривайте сторінку. Екран не гаснутиме, а сторінка затемниться."],
          ["volume", "Увімкніть гучність", "Сигнал грає з гучністю медіа. Додайте її перед сном."],
        ],
      }
    : {
        title: "Як це працює",
        lead: "Будильник стежить за тривогою, поки вкладка із сайтом відкрита.",
        cards: [
          ["computer", "Не закривайте вкладку", "Її можна не тримати на виду. У фоновій вкладці браузер перевіряє тривогу рідше, приблизно раз на хвилину."],
          ["power", "Не присипляйте комп'ютер", "Вимкніть сплячий режим на ніч і не закривайте кришку ноутбука, інакше браузер зупиниться."],
          ["volume", "Увімкніть звук", "Перевірте, що звук не вимкнено, а колонки чи навушники під'єднано."],
        ],
      };

function renderHowto() {
  $("howto-title").textContent = HOWTO.title;
  $("howto-lead").textContent = HOWTO.lead;
  $("row-howto-title").textContent = HOWTO.title;
  $("howto-cards").innerHTML = HOWTO.cards
    .map(([ic, title, text, id]) => `
      <div class="card info"${id ? ` id="${id}"` : ""}>
        <span class="badge">${icon(ic)}</span>
        <div><h3>${title}</h3><p>${text}</p></div>
      </div>`)
    .join("");
}

function render() {
  const idle = watch.phase === "IDLE";
  const ringing = watch.phase === "RINGING" || watch.phase === "SNOOZED";
  const next = nextSchedule();

  $("region-name").textContent = settings.region.name;
  $("region-chip").disabled = !idle;
  $("region-chip").querySelector("[data-icon=down]").hidden = !idle;

  setOrbPhase($("main-orb"), watch.phase);
  $("hint-text").textContent = `${TAP}, щоб ${
    idle ? (next ? "увімкнути на ніч" : "увімкнути") : ringing ? "вимкнути" : "скасувати"}`;
  $("title").textContent = {
    IDLE: next ? `Будильник о ${fmtMinutes(next.alarm.minutes)}` : "Будильник вимкнено",
    SCHEDULED: `Будильник о ${hhmm(new Date(watch.scheduleAt))}`,
    WAITING_ALERT: "Очікування тривоги",
    ALERT: "Триває тривога",
    CLEARING: "Відбій тривоги",
    RINGING: "Відбій!",
    SNOOZED: "Відкладено",
  }[watch.phase];
  $("subtitle").textContent =
    watch.phase === "RINGING" ? watch.reason
      : watch.phase === "SCHEDULED"
        ? watch.evaluating ? watch.text
          : `${watch.shiftText ? `${watch.shiftText}.` : `Спрацює ${describeWhen(watch.scheduleAt)}.`} Якщо тоді буде тривога, розбудить після відбою. Не закривайте сторінку.`
      : watch.text || (interrupted
        ? `Будильник було перервано: сторінку закрили або оновили. ${finePointer ? "Натисніть на місяць" : "Торкніться місяця"}, щоб продовжити.`
        : next
          ? `Спрацює ${describeWhen(next.at)}. ${TAP} перед сном і не закривайте сторінку.`
          : "Якщо тривога застала під час сну, будильник пролунає одразу після відбою.");

  const source = $("source");
  source.hidden = idle || !watch.source;
  if (!source.hidden) {
    source.querySelector("span").textContent = `${watch.source} · перевірено о ${new Date(watch.checkedAt).toLocaleTimeString("uk-UA")}`;
    source.classList.toggle("stale", Date.now() - watch.checkedAt > STALE_MS);
  }

  $("cutoff-value").textContent = settings.cutoff >= 0 ? fmtMinutes(settings.cutoff) : "Без обмеження";
  $("conn-value").textContent = settings.alarmOnNoConnection ? "Будити" : "Не будити";
  $("tile-cutoff").disabled = !idle;
  $("tile-conn").disabled = !idle;
  $("install-banner").hidden = !(isIOS && !isStandalone());

  // Попередження перед сном, поки є увімкнений будильник на час і сторінка ще не стежить.
  const issues = idle && next && settings.bedtimeCheck ? bedtimeIssues() : [];
  $("bedtime-card").hidden = !issues.length;
  $("bedtime-list").innerHTML = issues.map((t) => `<li>${esc(t)}</li>`).join("");

  $("settings-locked").hidden = idle;
  for (const id of ["row-region", "row-cutoff", "row-test", "row-rules", "row-stable", "row-sunrise",
    "row-dismiss", "row-math-level", "row-math-count", "row-shake-count"]) $(id).disabled = !idle;
  for (const id of ["conn-switch", "bedtime-switch", "notice-switch", "voice-switch"]) $(id).disabled = !idle;
  $("row-dismiss-value").textContent = DISMISS[settings.dismissTask];
  $("row-math-level").hidden = $("row-math-count").hidden = settings.dismissTask !== "MATH";
  $("row-shake-count").hidden = settings.dismissTask !== "SHAKE";
  $("row-math-level-value").textContent = MATH_LEVELS.find(([v]) => v === settings.mathLevel)?.[1] ?? "";
  $("row-math-count-value").textContent = `${settings.mathCount} ${plural(settings.mathCount, "приклад", "приклади", "прикладів")}`;
  $("row-shake-count-value").textContent = `${settings.shakeCount} ${plural(settings.shakeCount, "раз", "рази", "разів")}`;
  $("voice-switch").checked = settings.voice;
  $("voice-note").textContent = !synth ? "Цей браузер не вміє говорити"
    : ukVoice ? "Під час сигналу голос назве причину й час" : "Український голос недоступний у цьому браузері";
  const hooksOn = settings.webhooks.filter((h) => h.enabled).length;
  $("row-hooks-value").textContent = hooksOn
    ? `Увімкнено ${hooksOn} ${plural(hooksOn, "вебхук", "вебхуки", "вебхуків")}`
    : "Запит на вашу адресу, коли настав відбій чи задзвонив будильник";
  if (!$("hooks").hidden) renderHooks();
  $("conn-switch").checked = settings.alarmOnNoConnection;
  $("bedtime-switch").checked = settings.bedtimeCheck;
  $("notice-switch").checked = settings.alertStartNotice;
  $("push-switch").checked = settings.push;
  $("row-region-value").textContent = [settings.region.name, settings.region.detail].filter(Boolean).join(", ");
  $("row-cutoff-value").textContent = $("cutoff-value").textContent;
  $("row-sound-value").textContent = soundName(settings.sound, settings.customName);
  $("row-stable-value").textContent = settings.stableClear ? `${settings.stableClear} хв` : "Ні, будити одразу";
  $("row-sunrise-value").textContent = settings.sunrise ? `За ${settings.sunrise} хв до будильника на час` : "Вимкнено";
  $("row-rules-value").textContent = describeRules();
  $("row-sound").disabled = !idle;
  $("region-next").textContent = `Далі: ${settings.region.name}`;
  renderSchedule(idle, next);
  if (!$("rules").hidden) renderRules(idle);
  if (!$("rule-edit").hidden) renderRuleEdit(idle);
}

function describeRules() {
  const on = settings.nightRules.filter((r) => r.enabled);
  if (!on.length) return "Вимкнено";
  return on.length === 1 ? describeRule(on[0]) : `${on.length} ${plural(on.length, "правило", "правила", "правил")}`;
}

// ---------- Екрани будильників на час ----------

function renderSchedule(idle, next) {
  const on = settings.alarms.filter((a) => a.enabled).length;
  const more = on > 1 ? ` · ще ${on - 1}` : "";
  $("schedule-card-switch").checked = on > 0;
  $("schedule-card-switch").disabled = !idle;
  $("schedule-card-time").textContent = next ? fmtMinutes(next.alarm.minutes) : "Вимкнено";
  $("schedule-card-sub").textContent = next
    ? cap(describeWhen(next.at)) + more
    : "Розбудить у заданий час, а під час тривоги — після відбою";
  $("row-schedule-value").textContent = next ? `${fmtMinutes(next.alarm.minutes)} · ${describeDays(next.alarm.days)}${more}` : "Вимкнено";
  if (!$("schedule").hidden) renderAlarmList(idle, next);
  if (!$("alarm-edit").hidden) renderAlarmEdit(idle);
}

function renderAlarmList(idle, next) {
  $("schedule-next").textContent = next ? `Найближчий спрацює ${describeWhen(next.at)}` : "Усі будильники вимкнено";
  const sorted = [...settings.alarms].sort((a, b) => a.minutes - b.minutes);
  $("alarm-list").innerHTML = sorted.map((a) => {
    const sub = [
      describeDays(a.days),
      a.place ? a.place.name : "",
      a.nightRule ? "пізніше після нічної тривоги" : "",
    ].filter(Boolean).join(" · ");
    return `
      <div class="alarm-item${a.enabled ? "" : " off"}">
        <button class="alarm-open" data-open="${a.id}">
          <b>${fmtMinutes(a.minutes)}</b>
          <small>${a.nightRule ? `<span class="inline-icon">${icon("bedtime")}</span>` : ""}${esc(sub)}</small>
        </button>
        <input type="checkbox" class="switch" data-toggle="${a.id}" aria-label="Будильник о ${fmtMinutes(a.minutes)}"${a.enabled ? " checked" : ""}${idle ? "" : " disabled"}>
      </div>`;
  }).join("");
  $("alarm-add").disabled = !idle;
}

let editingAlarm = 0;

function renderAlarmEdit(idle) {
  const a = findAlarm(editingAlarm);
  if (!a) return;
  $("alarm-switch").checked = a.enabled;
  if (document.activeElement !== $("alarm-time")) $("alarm-time").value = fmtMinutes(a.minutes);
  const at = a.enabled ? nextAlarmAt(a) : 0;
  $("alarm-next").textContent = at ? `Спрацює ${describeWhen(at)}` : "Будильник вимкнено";
  $("alarm-days").innerHTML = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    .map((d, i) => `<button data-bit="${1 << i}" class="${a.days & (1 << i) ? "on" : ""}"${idle ? "" : " disabled"}>${d}</button>`)
    .join("");
  $("alarm-presets").innerHTML = [["Будні", WEEKDAYS], ["Щодня", EVERY_DAY], ["Вихідні", WEEKEND], ["Один раз", 0]]
    .map(([label, days]) => `<button data-days="${days}" class="${a.days === days ? "on" : ""}"${idle ? "" : " disabled"}>${label}</button>`)
    .join("");
  const custom = ![WEEKDAYS, EVERY_DAY, WEEKEND].includes(a.days);
  $("alarm-days-note").hidden = !custom;
  $("alarm-days-note").textContent = a.days === 0 ? "Один раз: після спрацювання будильник вимкнеться" : describeDays(a.days);

  $("alarm-place-value").textContent = a.place ? a.place.name : `Основне: ${settings.region.name}`;
  $("alarm-place-sub").textContent = a.place
    ? a.place.detail || "Будильник перевірятиме тривогу тут"
    : "Можна обрати інше місце, наприклад де ви будете зранку";
  $("row-alarm-place-reset").hidden = !a.place;
  $("alarm-place-main").textContent = settings.region.name;
  $("alarm-night-switch").checked = a.nightRule;
  $("alarm-night-note").textContent = a.nightRule
    ? `Правила: ${describeRules().toLowerCase() === "вимкнено" ? "поки не задано" : describeRules()}`
    : "Якщо вночі була тривога, спрацює пізніше за правилами";
  for (const id of ["alarm-switch", "alarm-time", "alarm-night-switch", "row-alarm-place", "row-alarm-place-reset", "alarm-delete"]) {
    $(id).disabled = !idle;
  }
}

function updateAlarm(patch) {
  const a = findAlarm(editingAlarm);
  if (!a) return;
  Object.assign(a, patch);
  saveSettings();
  render();
}

// ---------- Правила нічної тривоги ----------

const RULE_TEMPLATES = [
  { from: 0, to: 360, minDuration: 0, action: "AT", at: 540, later: 120 },
  { from: 0, to: 360, minDuration: 0, action: "LATER", at: 540, later: 120 },
  { from: 22 * 60, to: 360, minDuration: 180, action: "SKIP", at: 540, later: 120 },
];
const DURATIONS = [[0, "Будь-скільки"], [15, "Понад 15 хв"], [30, "Понад 30 хв"], [60, "Понад 1 год"], [120, "Понад 2 год"], [180, "Понад 3 год"], [240, "Понад 4 год"]];
const LATER = [[30, "На 30 хв"], [60, "На 1 год"], [90, "На 1 год 30 хв"], [120, "На 2 год"], [180, "На 3 год"]];

$("rule-min").innerHTML = DURATIONS.map(([v, t]) => `<option value="${v}">${t}</option>`).join("");
$("rule-later").innerHTML = LATER.map(([v, t]) => `<option value="${v}">${t}</option>`).join("");

function renderRules(idle) {
  const users = settings.alarms.filter((a) => a.nightRule);
  $("rules-usage").textContent = users.length
    ? `Діють для: ${users.map((a) => `${fmtMinutes(a.minutes)} (${describeDays(a.days).toLowerCase()})`).join(", ")}.`
    : "Правила діють лише для будильників на час, у яких увімкнено «Пізніше після нічної тривоги». Зараз таких немає.";
  $("rules-list").hidden = !settings.nightRules.length;
  $("rules-empty").hidden = settings.nightRules.length > 0;
  $("rules-list").innerHTML = settings.nightRules.map((r) => `
    <div class="row split${idle ? "" : " disabled"}">
      <button class="row-main" data-edit="${r.id}">
        <span class="badge">${icon(r.action === "SKIP" ? "close" : "bedtime")}</span>
        <span><b>${esc(describeRule(r))}</b></span>
      </button>
      <input type="checkbox" class="switch" data-toggle="${r.id}" aria-label="Увімкнено"${r.enabled ? " checked" : ""}${idle ? "" : " disabled"}>
    </div>`).join("");
  $("rule-add").disabled = !idle;
}

let editingRule = 0;
const findRule = (id) => settings.nightRules.find((r) => r.id === id);

function renderRuleEdit(idle) {
  const r = findRule(editingRule);
  if (!r) return;
  $("rule-switch").checked = r.enabled;
  $("rule-summary").textContent = describeRule(r);
  for (const [id, value] of [["rule-from", r.from], ["rule-to", r.to], ["rule-at", r.at]]) {
    if (document.activeElement !== $(id)) $(id).value = fmtMinutes(value);
  }
  $("rule-min").value = String(r.minDuration);
  $("rule-action").value = r.action;
  $("rule-later").value = String(r.later);
  $("rule-at-field").hidden = r.action !== "AT";
  $("rule-later").hidden = r.action !== "LATER";
  for (const id of ["rule-switch", "rule-from", "rule-to", "rule-at", "rule-min", "rule-action", "rule-later", "rule-delete"]) {
    $(id).disabled = !idle;
  }
}

function updateRule(patch) {
  const r = findRule(editingRule);
  if (!r) return;
  Object.assign(r, patch);
  saveSettings();
  render();
}

// ---------- Історія ----------

function renderHistory() {
  const list = loadHistory().reverse();
  $("history-empty").hidden = list.length > 0;
  $("history-clear").hidden = !list.length;
  $("history-list").innerHTML = list.map((s) => {
    const day = cap(new Date(s.start).toLocaleDateString("uk-UA", { weekday: "short", day: "numeric", month: "long" }));
    const span = `${hhmm(new Date(s.start))}–${s.end ? hhmm(new Date(s.end)) : "триває"}`;
    return `
      <div class="group pad night-card">
        <div class="night-head"><b>${esc(day)}</b><small>${span}</small></div>
        <ul class="events">${s.events.map((e) => `<li><time>${hhmm(new Date(e.at))}</time><span>${esc(e.text)}</span></li>`).join("")}</ul>
      </div>`;
  }).join("");
}

// ---------- Автоматичний нічний режим, вимкнення, вебхуки ----------

const DISMISS = { NONE: "Одним дотиком", MATH: "Розв'язати приклад", SHAKE: "Струснути телефон" };
const MATH_LEVELS = [["EASY", "Легкі: 34 + 57"], ["MEDIUM", "Середні: 47 × 6"], ["HARD", "Складні: 23 × 7 + 58"]];

let editingHook = 0;
const findHook = (id) => settings.webhooks.find((h) => h.id === id);

function renderHooks() {
  const list = settings.webhooks;
  $("hooks-empty").hidden = list.length > 0;
  $("hooks-list").hidden = !list.length;
  $("hooks-list").innerHTML = list.map((h) => {
    const events = HOOK_EVENTS.filter(([id]) => h.events?.includes(id)).map(([, name]) => name.toLowerCase()).join(", ");
    return `
    <div class="row split">
      <button class="row-main" data-edit="${h.id}">
        <span class="badge">${icon("home")}</span>
        <span><b>${esc(h.name || h.url || "Без назви")}</b><small>${esc(events || "Жодної події")}</small></span>
      </button>
      <input type="checkbox" class="switch" data-toggle="${h.id}" aria-label="Увімкнено"${h.enabled ? " checked" : ""}>
    </div>`;
  }).join("");
}

function renderHookEdit() {
  const h = findHook(editingHook);
  if (!h) return;
  $("hook-switch").checked = h.enabled;
  for (const [id, key] of [["hook-name", "name"], ["hook-url", "url"], ["hook-headers", "headers"], ["hook-body", "body"]]) {
    if (document.activeElement !== $(id)) $(id).value = h[key] || "";
  }
  $("hook-method").value = h.method || "POST";
  $("hook-body").placeholder = DEFAULT_BODY;
  $("hook-body-field").hidden = h.method === "GET";
  $("hook-url-warn").hidden = !h.url || browserCanSend(h.url.replace(/\{[a-z_]+\}/g, "x"));
  $("hook-events").innerHTML = HOOK_EVENTS.map(([id, name]) => `
    <label class="check-row"><span>${name}</span><input type="checkbox" data-event="${id}"${h.events?.includes(id) ? " checked" : ""}></label>`).join("");
  $("hook-last").textContent = h.last || "";
  $("hook-test").disabled = !h.url;
}

function updateHook(patch, { quiet = false } = {}) {
  const h = findHook(editingHook);
  if (!h) return;
  Object.assign(h, patch);
  saveSettings();
  // Під час набору тексту не перемальовуємо поля, лише те, що від них залежить.
  if (quiet) {
    $("hook-url-warn").hidden = !h.url || browserCanSend(h.url.replace(/\{[a-z_]+\}/g, "x"));
    $("hook-test").disabled = !h.url;
  } else {
    render();
    renderHookEdit();
  }
}

// ---------- Статистика ----------

const statTile = (value, label) => `<div class="stat"><b>${esc(value)}</b><small>${esc(label)}</small></div>`;

async function renderStats() {
  const now = Date.now();
  const sessions = loadHistory();
  const mine = nightStats(sessions, now);
  $("stats-mine").innerHTML = [
    statTile(mine.sessions, plural(mine.sessions, "ніч з будильником", "ночі з будильником", "ночей з будильником")),
    statTile(mine.alertNights, plural(mine.alertNights, "ніч з тривогою", "ночі з тривогою", "ночей з тривогою")),
    statTile(mine.rings, plural(mine.rings, "сигнал", "сигнали", "сигналів")),
    statTile(mine.snoozes, plural(mine.snoozes, "відкладення", "відкладення", "відкладень")),
    statTile(mine.shifts, "перенесено правилами"),
    statTile(mine.dismissDelay == null ? "—" : formatDuration(Math.max(1, Math.round(mine.dismissDelay))), "від сигналу до вимкнення"),
  ].join("");
  const untyped = sessions.some((s) => s.start >= now - 30 * 86_400_000 && s.events.some((e) => !e.type));
  $("stats-mine-note").textContent = !mine.sessions
    ? "Тут з'явиться статистика, коли будильник попрацює кілька ночей."
    : untyped ? "Записи, зроблені до оновлення, враховано не повністю." : "";

  const region = settings.region;
  $("stats-region-title").textContent = `Тривоги: ${region.name}`;
  $("stats-region").innerHTML = "";
  $("stats-chart-box").hidden = true;
  if (!region.sirenId) {
    $("stats-region-note").textContent = "Для цього місця немає історії тривог.";
    return;
  }
  $("stats-region-note").textContent = "Завантаження…";
  let intervals;
  try {
    intervals = await nightIntervals(region);
  } catch {
    $("stats-region-note").textContent = "Не вдалося завантажити дані про тривоги. Перевірте інтернет.";
    return;
  }
  if ($("stats").hidden) return;
  const r = regionStats(intervals, now);
  if (!r) {
    $("stats-region-note").textContent = "Останнім часом тривог не було.";
    return;
  }
  const bars = nightBars(intervals, now);
  const top = Math.max(60, ...bars.map((b) => b.minutes || 0));
  $("stats-bars").innerHTML = bars.map((b) => {
    const day = new Date(b.day).toLocaleDateString("uk-UA", { day: "numeric", month: "short" });
    if (b.minutes === null) return `<i class="none" title="${esc(day)}: немає даних"></i>`;
    const title = b.minutes ? `${day}: ${formatDuration(b.minutes)}` : `${day}: без тривоги`;
    return `<i class="${b.minutes ? "" : "zero"}" style="height:${b.minutes ? Math.max(6, Math.round((b.minutes / top) * 100)) : 3}%" title="${esc(title)}"></i>`;
  }).join("");
  $("stats-bars").setAttribute("aria-label", `Ночей з тривогою за 14 днів: ${bars.filter((b) => b.minutes).length}`);
  $("stats-axis-from").textContent = new Date(bars[0].day).toLocaleDateString("uk-UA", { day: "numeric", month: "short" });
  $("stats-chart-box").hidden = false;
  $("stats-region").innerHTML = [
    // Даних може бути менше, ніж за тиждень: тоді кажемо, з якого дня.
    r.oldest > now - 7 * 86_400_000
      ? statTile(r.total, `${plural(r.total, "тривога", "тривоги", "тривог")} з ${new Date(r.oldest).toLocaleDateString("uk-UA", { day: "numeric", month: "long" })}`)
      : statTile(r.week, `${plural(r.week, "тривога", "тривоги", "тривог")} за 7 днів`),
    statTile(formatDuration(Math.round(r.avg)), "середня тривалість"),
    statTile(formatDuration(Math.round(r.longest)), "найдовша"),
    statTile(`${Math.round(r.nightShare * 100)} %`, "тривог уночі (00:00–06:00)"),
    r.endHour == null ? "" : statTile(`${String(r.endHour).padStart(2, "0")}:00`, "найчастіше закінчуються нічні"),
  ].join("");
  const since = new Date(r.oldest).toLocaleDateString("uk-UA", { day: "numeric", month: "long" });
  $("stats-region-note").textContent =
    `За даними siren.pp.ua: останні ${r.total} ${plural(r.total, "тривога", "тривоги", "тривог")} з ${since}.`;
}

// ---------- Вибір зі списку ----------

/** Діалог з варіантами; повертає обране значення або undefined, якщо скасували. */
function choose(title, options, selected, note = "") {
  $("choice-title").textContent = title;
  $("choice-note").hidden = !note;
  $("choice-note").textContent = note;
  $("choice-list").innerHTML = options.map(([value, label], i) => `
    <button value="${i}" class="choice${value === selected ? " on" : ""}">
      <span>${esc(label)}</span>${value === selected ? `<span class="check">${icon("check")}</span>` : ""}
    </button>`).join("");
  const dialog = $("choice-dialog");
  dialog.returnValue = ""; // Esc закриває діалог без нового значення
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => {
      const i = dialog.returnValue;
      resolve(i === "" ? undefined : options[+i]?.[0]);
    }, { once: true });
  });
}

// ---------- Екрани ----------

const SCREENS = ["get-android", "install-ios", "onboarding", "home", "settings", "region", "sounds",
  "schedule", "alarm-edit", "rules", "rule-edit", "history", "hooks", "hook-edit", "stats"];
let regionReturn = "home";
let regionMode = "main"; // main — основне місце, place — місце для будильника на час
let howtoOnly = false;

function show(id) {
  stopPreview();
  SCREENS.forEach((s) => { $(s).hidden = s !== id; });
  window.scrollTo(0, 0);
  render();
}

function openRegion() {
  regionMode = "main";
  regionReturn = $("settings").hidden ? "home" : "settings";
  $("region-title").textContent = "Регіон";
  screenPicker.reset();
  show("region");
}

// Знайомство з програмою
let step = 0;
function goStep(n) {
  step = n;
  document.querySelectorAll("#onboarding .step").forEach((s) => s.classList.toggle("active", +s.dataset.step === n));
  document.querySelectorAll("#onboarding .steps i").forEach((d, i) => d.classList.toggle("on", i <= n));
  $("install-card")?.classList.toggle("done", isStandalone());
}
document.querySelectorAll("#onboarding [data-next]").forEach((b) =>
  b.addEventListener("click", () => {
    // «Як це працює» з налаштувань: дві сторінки туру (як і що ще вміє), потім назад.
    if (howtoOnly && step >= 2) {
      howtoOnly = false;
      show("settings");
    } else {
      goStep(step + 1);
    }
  }));
document.querySelector("#onboarding [data-finish]").addEventListener("click", () => {
  settings.onboarded = true;
  saveSettings();
  show("home");
});
const onSoundChange = () => {
  saveSettings();
  applySound();
  render();
};
const soundLists = [
  mountSoundList($("onboarding-sounds"), { settings, onChange: () => { onSoundChange(); soundLists[1].render(); }, onError: toast }),
  mountSoundList($("sound-list"), { settings, onChange: () => { onSoundChange(); soundLists[0].render(); }, onError: toast }),
];
$("row-sound").addEventListener("click", () => show("sounds"));

// Вибір регіону
const pick = (region) => {
  settings.region = region;
  saveSettings();
  render();
};
const onboardingPicker = mountPicker($("onboarding-picker"), {
  getSelected: () => settings.region,
  onPick: (r) => { pick(r); goStep(4); },
});
const sameRegion = (a, b) => a.sirenId === b.sirenId && a.name === b.name;
const screenPicker = mountPicker($("screen-picker"), {
  getSelected: () => (regionMode === "place" ? alarmRegion(findAlarm(editingAlarm)) : settings.region),
  onPick: (r) => {
    if (regionMode === "place") {
      updateAlarm({ place: sameRegion(r, settings.region) ? null : r });
      show("alarm-edit");
    } else {
      pick(r);
      show(regionReturn);
    }
  },
});

document.querySelectorAll("[data-back]").forEach((b) =>
  b.addEventListener("click", () => {
    if (!$("region").hidden) {
      if (!screenPicker.back()) show(regionMode === "place" ? "alarm-edit" : regionReturn);
    } else if (["sounds", "history", "hooks", "stats"].some((id) => !$(id).hidden)) {
      show("settings");
    } else if (!$("hook-edit").hidden) {
      renderHooks();
      show("hooks");
    } else if (!$("alarm-edit").hidden) {
      show("schedule");
    } else if (!$("schedule").hidden) {
      show(scheduleReturn);
    } else if (!$("rule-edit").hidden) {
      show("rules");
    } else if (!$("rules").hidden) {
      show(rulesReturn);
    } else {
      show("home");
    }
  }));

// Будильники на час
let scheduleReturn = "home";
function openSchedule() {
  scheduleReturn = $("settings").hidden ? "home" : "settings";
  show("schedule");
}
function openAlarm(id) {
  editingAlarm = id;
  show("alarm-edit");
}
$("schedule-card").addEventListener("click", (e) => {
  if (e.target.closest(".switch")) return;
  openSchedule();
});
$("schedule-card").addEventListener("keydown", (e) => {
  if ((e.key === "Enter" || e.key === " ") && !e.target.closest(".switch")) {
    e.preventDefault();
    openSchedule();
  }
});
$("row-schedule").addEventListener("click", openSchedule);
// Перемикач на картці вмикає чи вимикає всі будильники одразу; якщо жодного немає — створює стандартний.
$("schedule-card-switch").addEventListener("change", (e) => {
  if (e.target.checked) {
    if (!settings.alarms.length) settings.alarms.push(newAlarm(1));
    else settings.alarms[0].enabled = true;
  } else {
    settings.alarms.forEach((a) => { a.enabled = false; });
  }
  saveSettings();
  render();
});
$("alarm-list").addEventListener("click", (e) => {
  const open = e.target.closest("[data-open]");
  if (open) openAlarm(+open.dataset.open);
});
$("alarm-list").addEventListener("change", (e) => {
  const a = findAlarm(+e.target.dataset.toggle);
  if (!a) return;
  a.enabled = e.target.checked;
  saveSettings();
  render();
});
$("alarm-add").addEventListener("click", () => {
  const id = Math.max(0, ...settings.alarms.map((a) => a.id)) + 1;
  settings.alarms.push(newAlarm(id));
  saveSettings();
  openAlarm(id);
});
$("alarm-switch").addEventListener("change", (e) => updateAlarm({ enabled: e.target.checked }));
$("alarm-time").addEventListener("change", (e) => {
  // Змінили час — отже, хочуть, щоб будильник працював.
  if (e.target.value) updateAlarm({ minutes: parseTime(e.target.value), enabled: true });
});
$("alarm-days").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-bit]");
  if (b) updateAlarm({ days: findAlarm(editingAlarm).days ^ Number(b.dataset.bit) });
});
$("alarm-presets").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-days]");
  if (b) updateAlarm({ days: Number(b.dataset.days) });
});
$("row-alarm-place").addEventListener("click", () => {
  regionMode = "place";
  $("region-title").textContent = "Місце для будильника";
  screenPicker.reset();
  show("region");
});
$("row-alarm-place-reset").addEventListener("click", () => updateAlarm({ place: null }));
$("alarm-night-switch").addEventListener("change", (e) => {
  updateAlarm({ nightRule: e.target.checked });
  // Правил ще немає — одразу показуємо, де їх задати.
  if (e.target.checked && !settings.nightRules.length) openRules();
});
$("row-alarm-rules").addEventListener("click", () => openRules());
$("alarm-delete").addEventListener("click", () => {
  settings.alarms = settings.alarms.filter((a) => a.id !== editingAlarm);
  saveSettings();
  show("schedule");
});

// Правила нічної тривоги
let rulesReturn = "settings";
function openRules() {
  rulesReturn = !$("alarm-edit").hidden ? "alarm-edit" : "settings";
  show("rules");
}
function openRule(id) {
  editingRule = id;
  show("rule-edit");
}
$("row-rules").addEventListener("click", openRules);
$("rules-list").addEventListener("click", (e) => {
  const b = e.target.closest("[data-edit]");
  if (b) openRule(+b.dataset.edit);
});
$("rules-list").addEventListener("change", (e) => {
  const r = findRule(+e.target.dataset.toggle);
  if (!r) return;
  r.enabled = e.target.checked;
  saveSettings();
  render();
});
$("rule-add").addEventListener("click", async () => {
  const i = await choose("Нове правило", RULE_TEMPLATES.map((t, i) => [i, describeRule(t)]), -1,
    "Оберіть основу, а потім налаштуйте проміжок, тривалість і дію.");
  if (i === undefined) return;
  const id = Math.max(0, ...settings.nightRules.map((r) => r.id)) + 1;
  settings.nightRules.push({ id, enabled: true, ...RULE_TEMPLATES[i] });
  saveSettings();
  openRule(id);
});
$("rule-switch").addEventListener("change", (e) => updateRule({ enabled: e.target.checked }));
for (const [id, key] of [["rule-from", "from"], ["rule-to", "to"], ["rule-at", "at"]]) {
  $(id).addEventListener("change", (e) => { if (e.target.value) updateRule({ [key]: parseTime(e.target.value) }); });
}
$("rule-min").addEventListener("change", (e) => updateRule({ minDuration: +e.target.value }));
$("rule-action").addEventListener("change", (e) => updateRule({ action: e.target.value }));
$("rule-later").addEventListener("change", (e) => updateRule({ later: +e.target.value }));
$("rule-delete").addEventListener("click", () => {
  settings.nightRules = settings.nightRules.filter((r) => r.id !== editingRule);
  saveSettings();
  show("rules");
});

// Історія ночей
$("row-history").addEventListener("click", () => {
  renderHistory();
  show("history");
});
$("history-clear").addEventListener("click", () => {
  // Поточний сеанс лишаємо, щоб у нього й далі записувалися події.
  const open = watch.phase !== "IDLE" ? loadHistory().filter((s) => !s.end).slice(-1) : [];
  save(HISTORY_KEY, open);
  renderHistory();
});

// Статистика
$("row-stats").addEventListener("click", () => {
  show("stats");
  renderStats();
});

// Вимкнення будильника
$("row-dismiss").addEventListener("click", async () => {
  const value = await choose("Як вимикати будильник", Object.entries(DISMISS), settings.dismissTask,
    "Щоб не вимкнути крізь сон. «Ще 5 хвилин» працює без завдання. Спробувати можна через «Перевірити звук будильника».");
  if (value === undefined) return;
  settings.dismissTask = value;
  saveSettings();
  render();
});
$("row-math-level").addEventListener("click", async () => {
  const value = await choose("Складність прикладів", MATH_LEVELS, settings.mathLevel);
  if (value === undefined) return;
  settings.mathLevel = value;
  saveSettings();
  render();
});
$("row-math-count").addEventListener("click", async () => {
  const value = await choose("Скільки прикладів", [1, 2, 3, 4, 5].map((n) => [n, String(n)]), settings.mathCount);
  if (value === undefined) return;
  settings.mathCount = value;
  saveSettings();
  render();
});
$("row-shake-count").addEventListener("click", async () => {
  const value = await choose("Скільки струсів", [10, 20, 30, 50].map((n) => [n, `${n} разів`]), settings.shakeCount);
  if (value === undefined) return;
  settings.shakeCount = value;
  saveSettings();
  render();
});

// Розумний дім і вебхуки
$("row-hooks").addEventListener("click", () => {
  renderHooks();
  show("hooks");
});
function openHook(id) {
  editingHook = id;
  renderHookEdit();
  show("hook-edit");
}
$("hooks-list").addEventListener("click", (e) => {
  const b = e.target.closest("[data-edit]");
  if (b) openHook(+b.dataset.edit);
});
$("hooks-list").addEventListener("change", (e) => {
  const h = findHook(+e.target.dataset.toggle);
  if (!h) return;
  h.enabled = e.target.checked;
  saveSettings();
  render();
});
$("hook-add").addEventListener("click", () => {
  const id = Math.max(0, ...settings.webhooks.map((h) => h.id)) + 1;
  settings.webhooks.push({ id, enabled: true, name: "", url: "", method: "POST", events: ["clear"], headers: "", body: "", last: "" });
  saveSettings();
  openHook(id);
});
$("hook-switch").addEventListener("change", (e) => updateHook({ enabled: e.target.checked }));
for (const [id, key] of [["hook-name", "name"], ["hook-url", "url"], ["hook-headers", "headers"], ["hook-body", "body"]]) {
  $(id).addEventListener("input", (e) => updateHook({ [key]: e.target.value }, { quiet: true }));
}
$("hook-method").addEventListener("change", (e) => updateHook({ method: e.target.value }));
$("hook-events").addEventListener("change", (e) => {
  const h = findHook(editingHook);
  const ev = e.target.dataset.event;
  if (!h || !ev) return;
  const events = new Set(h.events || []);
  if (e.target.checked) events.add(ev);
  else events.delete(ev);
  updateHook({ events: HOOK_EVENTS.map(([id]) => id).filter((id) => events.has(id)) });
});
$("hook-test").addEventListener("click", async () => {
  const h = findHook(editingHook);
  if (!h?.url) return;
  $("hook-test").disabled = true;
  $("hook-last").textContent = "Надсилання…";
  await sendHook(h, hookVars("test", { place: settings.region.name, reason: "Перевірка" }));
  $("hook-test").disabled = false;
});
$("hook-delete").addEventListener("click", () => {
  settings.webhooks = settings.webhooks.filter((h) => h.id !== editingHook);
  saveSettings();
  renderHooks();
  show("hooks");
});

// Головний екран
$("main-orb").addEventListener("click", () => {
  if (watch.phase === "IDLE") armWithCheck();
  else finish();
});
$("region-chip").addEventListener("click", openRegion);
$("open-settings").addEventListener("click", () => show("settings"));
$("tile-cutoff").addEventListener("click", openCutoff);
$("tile-conn").addEventListener("click", () => {
  settings.alarmOnNoConnection = !settings.alarmOnNoConnection;
  saveSettings();
  render();
});

// Налаштування
const bindSwitch = (id, key) => $(id).addEventListener("change", (e) => {
  settings[key] = e.target.checked;
  saveSettings();
  render();
});
$("row-region").addEventListener("click", openRegion);
$("row-cutoff").addEventListener("click", openCutoff);
bindSwitch("conn-switch", "alarmOnNoConnection");
bindSwitch("bedtime-switch", "bedtimeCheck");
bindSwitch("notice-switch", "alertStartNotice");
bindSwitch("voice-switch", "voice");
$("row-stable").addEventListener("click", async () => {
  const value = await choose(
    "Чекати, щоб відбій утримався",
    [[0, "Ні, будити одразу"], [5, "5 хв"], [10, "10 хв"], [15, "15 хв"], [20, "20 хв"], [30, "30 хв"]],
    settings.stableClear,
    "Буває, що невдовзі після відбою тривогу оголошують знову. Будильник розбудить, лише якщо відбій триває заданий час.",
  );
  if (value === undefined) return;
  settings.stableClear = value;
  saveSettings();
  render();
});
$("row-sunrise").addEventListener("click", async () => {
  const value = await choose(
    "Світанок перед будильником",
    [[0, "Вимкнено"], [5, "За 5 хв"], [10, "За 10 хв"], [15, "За 15 хв"], [20, "За 20 хв"]],
    settings.sunrise,
    "Перед будильником на час нічний екран плавно світлішає, щоб прокидатися було легше. Сторінка має бути відкрита.",
  );
  if (value === undefined) return;
  settings.sunrise = value;
  saveSettings();
  render();
});
$("push-switch").addEventListener("change", async (e) => {
  const on = e.target.checked;
  e.target.disabled = true;
  const ok = on ? await enablePush() : (await disablePush(), true);
  e.target.disabled = false;
  if (ok) settings.push = on;
  saveSettings();
  render();
  if (ok && on) syncPush();
});
$("row-test").addEventListener("click", () => ring("Перевірка будильника", { test: true }));
$("row-howto").addEventListener("click", () => {
  howtoOnly = true;
  goStep(1);
  show("onboarding");
});

// Будильник
$("alarm-stop").addEventListener("click", dismiss);
$("alarm-snooze").addEventListener("click", () => {
  if (watch.test) finish();
  else snooze();
});
setInterval(() => {
  if (!$("alarm").hidden) $("alarm-clock").textContent = hhmm(new Date());
}, 1000);

// «Не будити після»
function openCutoff() {
  $("cutoff-input").value = fmtMinutes(settings.cutoff >= 0 ? settings.cutoff : 14 * 60 + 30);
  $("cutoff-dialog").showModal();
}
$("cutoff-dialog").addEventListener("close", () => {
  const result = $("cutoff-dialog").returnValue;
  if (result === "clear") {
    settings.cutoff = -1;
  } else if (result === "ok" && $("cutoff-input").value) {
    settings.cutoff = parseTime($("cutoff-input").value);
  }
  saveSettings();
  render();
});

// ---------- Сповіщення, коли сторінка закрита ----------
// Браузер може призупинити сторінку (iPhone у фоні, заблокований екран). Тоді за тривогою стежить
// сервер (worker/) і надсилає push-сповіщення. Сторінка повідомляє йому про кожну зміну стану.

const b64url = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

async function pushSubscription() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) return null;
  const reg = await navigator.serviceWorker.ready;
  return reg.pushManager.getSubscription();
}

async function enablePush() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    toast(isIOS && !isStandalone()
      ? "На iPhone сповіщення працюють лише для програми з початкового екрана"
      : "Цей браузер не підтримує сповіщення");
    return false;
  }
  if (await Notification.requestPermission() !== "granted") {
    toast("Сповіщення заборонено в налаштуваннях браузера");
    return false;
  }
  try {
    const reg = await navigator.serviceWorker.ready;
    const { key } = await getJSON("/push/key");
    const current = await reg.pushManager.getSubscription();
    if (!current) await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64url(key) });
    return true;
  } catch {
    toast("Не вдалося ввімкнути сповіщення. Спробуйте пізніше");
    return false;
  }
}

async function disablePush() {
  try {
    const sub = await pushSubscription();
    if (!sub) return;
    await sendPush(sub, null);
    await sub.unsubscribe();
  } catch {}
}

function sendPush(sub, body) {
  return fetch(`${PROXY}/push/watch`, {
    method: body ? "POST" : "DELETE",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body ?? { endpoint: sub.endpoint }),
    keepalive: true,
  });
}

let pushTimer = 0;
function syncPush() {
  if (!settings.push) return;
  clearTimeout(pushTimer);
  pushTimer = setTimeout(() => pushState().catch(() => {}), 500);
}

async function pushState() {
  const sub = await pushSubscription();
  if (!sub) return;
  // Сигнал уже звучить на сторінці або стеження вимкнено — серверу стежити нема за чим.
  if ((!CHECKING.includes(watch.phase) && watch.phase !== "SCHEDULED") || watch.test) {
    await sendPush(sub, null);
    return;
  }
  const region = watch.region || settings.region;
  let up = [];
  let down = [];
  if (region.sirenId) {
    try {
      const places = await getTree();
      up = ancestors(places, region.sirenId);
      down = [...descendants(places, region.sirenId)];
    } catch {}
  }
  const alarm = findAlarm(watch.alarmId);
  const scheduled = watch.phase === "SCHEDULED";
  await sendPush(sub, {
    subscription: sub.toJSON(),
    watch: {
      session: watch.sessionId,
      region: { sirenId: region.sirenId, ubilling: region.ubilling, name: region.name, up, down },
      sawAlert: watch.sawAlert,
      cutoffAt: watch.cutoffAt,
      scheduleAt: scheduled ? watch.scheduleAt : 0,
      shifted: scheduled && watch.shifted,
      scheduled: scheduled || watch.scheduleRun,
      label: scheduled ? hhmm(new Date(watch.scheduleAt)) : watch.scheduleLabel,
      stableMs: settings.stableClear * MIN,
      nightRules: scheduled && !watch.shifted && alarm?.nightRule ? settings.nightRules.filter((r) => r.enabled) : [],
      tz: TZ,
    },
  });
}

let toastTimer = 0;
function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 6000);
}

// ---------- Старт ----------

renderHowto();
applySound();
fillIcons();
document.querySelectorAll(".orb").forEach(setupOrb);
document.querySelector(".night-tip").textContent = `${TAP}, щоб показати`;
$("alarm-clock").textContent = hhmm(new Date());
render();
// Спершу пропонуємо найкращий варіант для пристрою: на Android — програму, на iPhone — встановлення на початковий екран.
function startApp() {
  if (settings.onboarded) {
    show("home");
  } else {
    goStep(0);
    show("onboarding");
  }
}
const GATE_KEY = "vidbiy.gate";
let gateSeen = false;
try { gateSeen = sessionStorage.getItem(GATE_KEY) === "1"; } catch {}
document.querySelectorAll("[data-continue]").forEach((b) =>
  b.addEventListener("click", () => {
    try { sessionStorage.setItem(GATE_KEY, "1"); } catch {}
    if (iosHelpReturn) {
      show(iosHelpReturn);
      iosHelpReturn = null;
    } else {
      startApp();
    }
  }));
let iosHelpReturn = null;
$("show-ios-help").addEventListener("click", () => {
  iosHelpReturn = "home";
  $("install-ios").querySelector("[data-continue]").textContent = "Зрозуміло";
  show("install-ios");
});

if (!gateSeen && isAndroid && !isStandalone()) show("get-android");
else if (!gateSeen && isIOS && !isStandalone()) show("install-ios");
else startApp();
setInterval(() => { if (watch.phase !== "IDLE") render(); }, 10_000);

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});

// ---------- Оновлення ----------

// Версію в адресу модуля додає scripts/build_web.py; без неї (запуск із теки web/) не перевіряємо.
const VERSION = new URL(import.meta.url).searchParams.get("v");
const UPDATE_CHECK_MS = 30 * 60_000;

// Нова версія підтягується перезавантаженням, але лише коли будильник вимкнено й відкрито головний екран:
// стеження, сигнал чи налаштування не перериваємо, перевіримо знову пізніше.
async function checkUpdate() {
  if (!VERSION || watch.phase !== "IDLE" || $("home").hidden) return;
  try {
    const res = await fetch(`version.json?t=${Date.now()}`, { cache: "no-store" });
    const { version } = await res.json();
    if (!version || version === VERSION || watch.phase !== "IDLE" || $("home").hidden) return;
    // Кеш Cloudflare може ще кілька хвилин віддавати стару сторінку: під ту саму версію перезавантажуємо лише раз.
    if (sessionStorage.getItem("vidbiy.update") === version) return;
    sessionStorage.setItem("vidbiy.update", version);
    location.reload();
  } catch {}
}
setTimeout(checkUpdate, 5_000);
setInterval(checkUpdate, UPDATE_CHECK_MS);
document.addEventListener("visibilitychange", () => { if (!document.hidden) checkUpdate(); });

