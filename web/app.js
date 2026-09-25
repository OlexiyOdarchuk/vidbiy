import { fillIcons, icon } from "./icons.js";
import { DEFAULT_REGION, ancestors, descendants, getJSON, getTree, mountPicker } from "./regions.js";
import { DEFAULT_SOUND, mountSoundList, soundName, soundUrl, stopPreview } from "./sounds.js";

const POLL_MS = 20_000;
const SNOOZE_MS = 5 * 60_000;
const NO_CONNECTION_MS = 5 * 60_000;
const NIGHT_AFTER_MS = 30_000;
const STALE_MS = 90_000;

const $ = (id) => document.getElementById(id);
const hhmm = (d) => d.toLocaleTimeString("uk-UA", { hour: "2-digit", minute: "2-digit" });
const fmtMinutes = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

// ---------- Налаштування ----------

const SETTINGS_KEY = "vidbiy.settings";
const WATCH_KEY = "vidbiy.watch";

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

const settings = load(SETTINGS_KEY, {
  region: DEFAULT_REGION,
  cutoff: -1,
  alarmOnNoConnection: true,
  onboarded: false,
  sound: DEFAULT_SOUND,
  customName: "",
  // Будильник на час: дні бітами, біт 0 — понеділок … біт 6 — неділя; 0 — один раз.
  schedule: { enabled: false, minutes: 7 * 60 + 30, days: 0b0011111 },
});
const saveSettings = () => save(SETTINGS_KEY, settings);

// ---------- Стан очікування ----------

const watch = {
  phase: "IDLE", // IDLE | SCHEDULED | WAITING_ALERT | ALERT | RINGING | SNOOZED
  scheduleAt: 0, // коли спрацює будильник на час (у фазі SCHEDULED)
  scheduleRun: false, // очікування запущене будильником на час
  scheduleLabel: "",
  runStartedAt: 0,
  text: "",
  reason: "",
  source: "",
  checkedAt: 0,
  sawAlert: false,
  lastOk: 0,
  cutoffAt: 0,
  timer: 0,
  generation: 0,
  test: false,
};

// Якщо сторінку закрили чи оновили під час очікування, запропонуємо продовжити.
let interrupted = load(WATCH_KEY, { armed: false }).armed ? load(WATCH_KEY, {}) : null;
const persistWatch = () =>
  save(WATCH_KEY, {
    armed: watch.phase !== "IDLE" && !watch.test,
    sawAlert: watch.sawAlert,
    cutoffAt: watch.cutoffAt,
    scheduleAt: watch.phase === "SCHEDULED" ? watch.scheduleAt : 0,
  });

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
  const scheduleAt = resume ? resume.scheduleAt || 0 : settings.schedule.enabled ? nextScheduleAt() : 0;
  if (scheduleAt) {
    Object.assign(watch, {
      phase: "SCHEDULED",
      text: "",
      reason: "",
      source: "",
      sawAlert: false,
      scheduleAt,
      scheduleRun: false,
      generation: watch.generation + 1,
      test: false,
    });
    interrupted = null;
    persistWatch();
    render();
    bumpNight();
    await unlocked;
    scheduleTick();
    return;
  }
  Object.assign(watch, {
    scheduleRun: false,
    phase: resume?.sawAlert ? "ALERT" : "WAITING_ALERT",
    text: "Перевірка стану тривоги…",
    reason: "",
    source: "",
    checkedAt: 0,
    sawAlert: !!resume?.sawAlert,
    lastOk: Date.now(),
    cutoffAt: resume?.cutoffAt || (settings.cutoff >= 0 ? nextOccurrence(settings.cutoff) : 0),
    generation: watch.generation + 1,
    test: false,
  });
  interrupted = null;
  persistWatch();
  render();
  bumpNight();
  await unlocked;
  tick();
}

const WEEKDAYS = 0b0011111;
const EVERY_DAY = 0b1111111;
const WEEKEND = 0b1100000;
const SHORT_DAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "нд"];
const IN_DAY = ["у понеділок", "у вівторок", "у середу", "у четвер", "у п'ятницю", "у суботу", "у неділю"];

function nextScheduleAt(now = new Date()) {
  const { minutes, days } = settings.schedule;
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

// Таймери у браузері можуть відставати, тому не чекаємо одним setTimeout, а звіряємо годинник.
function scheduleTick() {
  clearTimeout(watch.timer);
  if (watch.phase !== "SCHEDULED") return;
  if (Date.now() >= watch.scheduleAt) {
    startScheduledRun();
    return;
  }
  render();
  watch.timer = setTimeout(scheduleTick, Math.min(15_000, Math.max(500, watch.scheduleAt - Date.now())));
}

function startScheduledRun() {
  watch.scheduleLabel = hhmm(new Date(watch.scheduleAt));
  if (settings.schedule.days === 0) {
    settings.schedule.enabled = false; // одноразовий будильник вимикається, щойно спрацював
    saveSettings();
  }
  Object.assign(watch, {
    phase: "WAITING_ALERT",
    scheduleRun: true,
    runStartedAt: Date.now(),
    text: `Будильник о ${watch.scheduleLabel}: перевірка тривоги…`,
    lastOk: Date.now(),
    cutoffAt: settings.cutoff >= 0 ? nextOccurrence(settings.cutoff) : 0,
  });
  persistWatch();
  render();
  tick();
}

async function tick() {
  const gen = watch.generation;
  clearTimeout(watch.timer);
  if (watch.phase !== "WAITING_ALERT" && watch.phase !== "ALERT") return;

  if (watch.cutoffAt && Date.now() >= watch.cutoffAt) {
    finish(`Настав час ${hhmm(new Date(watch.cutoffAt))}, будильник вимкнено без сигналу`);
    return;
  }

  const region = settings.region;
  try {
    const r = await fetchStatus(region);
    if (gen !== watch.generation) return;
    watch.lastOk = Date.now();
    watch.source = r.source;
    watch.checkedAt = Date.now();
    if (r.status !== "NONE") {
      watch.sawAlert = true;
      watch.phase = "ALERT";
      const lead = watch.scheduleRun ? `Будильник о ${watch.scheduleLabel} чекає: ` : "";
      watch.text = r.status === "PARTIAL"
        ? `${lead}${lead ? "т" : "Т"}ривога в частині регіону. ${lead ? "Розбудить" : "Будильник пролунає"} після відбою в усьому регіоні.`
        : lead ? `${lead}триває тривога. Розбудить після відбою.` : "Будильник пролунає після відбою.";
      persistWatch();
    } else if (watch.sawAlert) {
      ring(`Відбій тривоги: ${region.name}`);
      return;
    } else if (watch.scheduleRun) {
      // Настав час будильника, а тривоги немає — будимо, як звичайний будильник.
      ring(`Будильник о ${watch.scheduleLabel}`);
      return;
    } else {
      watch.phase = "WAITING_ALERT";
      watch.text = "Зараз тривоги немає. Будильник пролунає після відбою наступної тривоги.";
    }
  } catch (e) {
    if (gen !== watch.generation) return;
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
  keepScreenOn();
  playAlarm();
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
  const gen = watch.generation;
  const reason = watch.reason;
  watch.timer = setTimeout(() => gen === watch.generation && ring(reason), SNOOZE_MS);
  render();
  bumpNight();
}

function finish(message = "") {
  clearTimeout(watch.timer);
  sound.pause();
  releaseScreen();
  Object.assign(watch, {
    phase: "IDLE", text: message, reason: "", source: "", sawAlert: false, test: false, scheduleRun: false, scheduleAt: 0,
  });
  watch.generation++;
  persistWatch();
  $("alarm").hidden = true;
  hideNight();
  render();
  if (message) toast(message);
}

// ---------- Нічний режим ----------

let nightTimer = 0;
let nightClock = 0;

function bumpNight() {
  clearTimeout(nightTimer);
  if (["SCHEDULED", "WAITING_ALERT", "ALERT", "SNOOZED"].includes(watch.phase)) {
    nightTimer = setTimeout(showNight, NIGHT_AFTER_MS);
  }
}

function showNight() {
  if (!["SCHEDULED", "WAITING_ALERT", "ALERT", "SNOOZED"].includes(watch.phase) || !$("alarm").hidden) return;
  $("night").hidden = false;
  updateNight();
  clearInterval(nightClock);
  nightClock = setInterval(updateNight, 1000);
}

function hideNight() {
  $("night").hidden = true;
  clearInterval(nightClock);
}

function updateNight() {
  const now = new Date();
  $("night-clock").textContent = hhmm(now);
  const status = {
    SCHEDULED: `Будильник о ${hhmm(new Date(watch.scheduleAt))}`,
    WAITING_ALERT: "Чекаю на тривогу",
    ALERT: "Триває тривога",
    SNOOZED: "Будильник відкладено",
  }[watch.phase] ?? "";
  $("night-status").textContent = `${status} · ${settings.region.name}`;
  // Трохи зсуваємо текст щохвилини, щоб не вигорав екран.
  if (now.getSeconds() === 0) {
    $("night").firstElementChild.style.transform =
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
  if (watch.phase === "WAITING_ALERT" || watch.phase === "ALERT") tick();
  if (watch.phase === "SCHEDULED") scheduleTick();
});

// ---------- Інтерфейс ----------

const ORB_ICONS = { IDLE: "bedtime", SCHEDULED: "alarm", WAITING_ALERT: "radar", ALERT: "campaign", RINGING: "alarm", SNOOZED: "snooze" };

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

  $("region-name").textContent = settings.region.name;
  $("region-chip").disabled = !idle;
  $("region-chip").querySelector("[data-icon=down]").hidden = !idle;

  setOrbPhase($("main-orb"), watch.phase);
  const sch = settings.schedule;
  const nextAt = sch.enabled ? nextScheduleAt() : 0;
  $("hint-text").textContent = `${TAP}, щоб ${
    idle ? (nextAt ? "увімкнути на ніч" : "увімкнути") : ringing ? "вимкнути" : "скасувати"}`;
  $("title").textContent = {
    IDLE: nextAt ? `Будильник о ${fmtMinutes(sch.minutes)}` : "Будильник вимкнено",
    SCHEDULED: `Будильник о ${hhmm(new Date(watch.scheduleAt))}`,
    WAITING_ALERT: "Очікування тривоги",
    ALERT: "Триває тривога",
    RINGING: "Відбій!",
    SNOOZED: "Відкладено",
  }[watch.phase];
  $("subtitle").textContent =
    watch.phase === "RINGING" ? watch.reason
      : watch.phase === "SCHEDULED"
        ? `Спрацює ${describeWhen(watch.scheduleAt)}. Якщо тоді буде тривога, розбудить після відбою. Не закривайте сторінку.`
      : watch.text || (interrupted
        ? `Будильник було перервано: сторінку закрили або оновили. ${finePointer ? "Натисніть на місяць" : "Торкніться місяця"}, щоб продовжити.`
        : nextAt
          ? `Спрацює ${describeWhen(nextAt)}. ${TAP} перед сном і не закривайте сторінку.`
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

  $("settings-locked").hidden = idle;
  for (const id of ["row-region", "row-cutoff", "row-test"]) $(id).disabled = !idle;
  $("conn-switch").disabled = !idle;
  $("conn-switch").checked = settings.alarmOnNoConnection;
  $("row-region-value").textContent = [settings.region.name, settings.region.detail].filter(Boolean).join(", ");
  $("row-cutoff-value").textContent = $("cutoff-value").textContent;
  $("row-sound-value").textContent = soundName(settings.sound, settings.customName);
  $("row-sound").disabled = !idle;
  $("region-next").textContent = `Далі: ${settings.region.name}`;
  renderSchedule(idle);
}

function renderSchedule(idle) {
  const sch = settings.schedule;
  $("schedule-card-switch").checked = sch.enabled;
  $("schedule-switch").checked = sch.enabled;
  $("schedule-card-time").textContent = sch.enabled ? fmtMinutes(sch.minutes) : "Вимкнено";
  $("schedule-card-sub").textContent = sch.enabled
    ? describeDays(sch.days)
    : "Розбудить у заданий час, а під час тривоги — після відбою";
  $("row-schedule-value").textContent = sch.enabled ? `${fmtMinutes(sch.minutes)} · ${describeDays(sch.days)}` : "Вимкнено";
  // Поки йде очікування, розклад не змінюємо: воно вже прив'язане до часу.
  for (const id of ["schedule-card-switch", "schedule-switch", "schedule-time"]) $(id).disabled = !idle;
  if (document.activeElement !== $("schedule-time")) $("schedule-time").value = fmtMinutes(sch.minutes);
  const nextAt = sch.enabled ? nextScheduleAt() : 0;
  $("schedule-next").textContent = nextAt ? `Спрацює ${describeWhen(nextAt)}` : "Будильник вимкнено";
  $("schedule-days").innerHTML = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    .map((d, i) => `<button data-bit="${1 << i}" class="${sch.days & (1 << i) ? "on" : ""}"${idle ? "" : " disabled"}>${d}</button>`)
    .join("");
  $("schedule-presets").innerHTML = [["Будні", WEEKDAYS], ["Щодня", EVERY_DAY], ["Вихідні", WEEKEND], ["Один раз", 0]]
    .map(([label, days]) => `<button data-days="${days}" class="${sch.days === days ? "on" : ""}"${idle ? "" : " disabled"}>${label}</button>`)
    .join("");
  const custom = ![WEEKDAYS, EVERY_DAY, WEEKEND].includes(sch.days);
  $("schedule-days-note").hidden = !custom;
  $("schedule-days-note").textContent = sch.days === 0
    ? "Один раз: після спрацювання будильник вимкнеться"
    : describeDays(sch.days);
}

function updateSchedule(patch) {
  Object.assign(settings.schedule, patch);
  saveSettings();
  render();
}

// ---------- Екрани ----------

const SCREENS = ["get-android", "install-ios", "onboarding", "home", "settings", "region", "sounds", "schedule"];
let regionReturn = "home";
let howtoOnly = false;

function show(id) {
  stopPreview();
  SCREENS.forEach((s) => { $(s).hidden = s !== id; });
  window.scrollTo(0, 0);
}

function openRegion() {
  regionReturn = $("settings").hidden ? "home" : "settings";
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
    if (howtoOnly) {
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
  onPick: (r) => { pick(r); goStep(3); },
});
const screenPicker = mountPicker($("screen-picker"), {
  getSelected: () => settings.region,
  onPick: (r) => { pick(r); show(regionReturn); },
});

document.querySelectorAll("[data-back]").forEach((b) =>
  b.addEventListener("click", () => {
    if (!$("region").hidden) {
      if (!screenPicker.back()) show(regionReturn);
    } else if (!$("sounds").hidden) {
      show("settings");
    } else if (!$("schedule").hidden) {
      show(scheduleReturn);
    } else {
      show("home");
    }
  }));

// Будильник на час
let scheduleReturn = "home";
function openSchedule() {
  scheduleReturn = $("settings").hidden ? "home" : "settings";
  show("schedule");
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
for (const id of ["schedule-card-switch", "schedule-switch"]) {
  $(id).addEventListener("change", (e) => updateSchedule({ enabled: e.target.checked }));
}
$("schedule-time").addEventListener("change", (e) => {
  if (!e.target.value) return;
  const [h, m] = e.target.value.split(":").map(Number);
  // Змінили час — отже, хочуть, щоб будильник працював.
  updateSchedule({ minutes: h * 60 + m, enabled: true });
});
$("schedule-days").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-bit]");
  if (b) updateSchedule({ days: settings.schedule.days ^ Number(b.dataset.bit) });
});
$("schedule-presets").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-days]");
  if (b) updateSchedule({ days: Number(b.dataset.days) });
});

// Головний екран
$("main-orb").addEventListener("click", () => {
  if (watch.phase === "IDLE") arm(interrupted);
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
$("row-region").addEventListener("click", openRegion);
$("row-cutoff").addEventListener("click", openCutoff);
$("conn-switch").addEventListener("change", (e) => {
  settings.alarmOnNoConnection = e.target.checked;
  saveSettings();
  render();
});
$("row-test").addEventListener("click", () => ring("Перевірка будильника", { test: true }));
$("row-howto").addEventListener("click", () => {
  howtoOnly = true;
  goStep(1);
  show("onboarding");
});

// Будильник
$("alarm-stop").addEventListener("click", () => finish());
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
    const [h, m] = $("cutoff-input").value.split(":").map(Number);
    settings.cutoff = h * 60 + m;
  }
  saveSettings();
  render();
});

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
