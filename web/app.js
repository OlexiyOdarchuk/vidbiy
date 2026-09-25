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
});
const saveSettings = () => save(SETTINGS_KEY, settings);

// ---------- Стан очікування ----------

const watch = {
  phase: "IDLE", // IDLE | WAITING_ALERT | ALERT | RINGING | SNOOZED
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
  save(WATCH_KEY, { armed: watch.phase !== "IDLE" && !watch.test, sawAlert: watch.sawAlert, cutoffAt: watch.cutoffAt });

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
  Object.assign(watch, {
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

function nextOccurrence(minutes) {
  const t = new Date();
  t.setHours(Math.floor(minutes / 60), minutes % 60, 0, 0);
  if (t <= new Date()) t.setDate(t.getDate() + 1);
  return t.getTime();
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
      watch.text = r.status === "PARTIAL"
        ? "Тривога в частині регіону. Будильник пролунає після відбою в усьому регіоні."
        : "Будильник пролунає після відбою.";
      persistWatch();
    } else if (watch.sawAlert) {
      ring(`Відбій тривоги: ${region.name}`);
      return;
    } else {
      watch.phase = "WAITING_ALERT";
      watch.text = "Зараз тривоги немає. Будильник пролунає після відбою наступної тривоги.";
    }
  } catch (e) {
    if (gen !== watch.generation) return;
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
  Object.assign(watch, { phase: "IDLE", text: message, reason: "", source: "", sawAlert: false, test: false });
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
  if (["WAITING_ALERT", "ALERT", "SNOOZED"].includes(watch.phase)) {
    nightTimer = setTimeout(showNight, NIGHT_AFTER_MS);
  }
}

function showNight() {
  if (!["WAITING_ALERT", "ALERT", "SNOOZED"].includes(watch.phase) || !$("alarm").hidden) return;
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
  const status = { WAITING_ALERT: "Чекаю на тривогу", ALERT: "Триває тривога", SNOOZED: "Будильник відкладено" }[watch.phase] ?? "";
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
});

// ---------- Інтерфейс ----------

const ORB_ICONS = { IDLE: "bedtime", WAITING_ALERT: "radar", ALERT: "campaign", RINGING: "alarm", SNOOZED: "snooze" };

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
  $("hint-text").textContent = `${TAP}, щоб ${idle ? "увімкнути" : ringing ? "вимкнути" : "скасувати"}`;
  $("title").textContent = {
    IDLE: "Будильник вимкнено",
    WAITING_ALERT: "Очікування тривоги",
    ALERT: "Триває тривога",
    RINGING: "Відбій!",
    SNOOZED: "Відкладено",
  }[watch.phase];
  $("subtitle").textContent =
    watch.phase === "RINGING" ? watch.reason
      : watch.text || (interrupted
        ? `Будильник було перервано: сторінку закрили або оновили. ${finePointer ? "Натисніть на місяць" : "Торкніться місяця"}, щоб продовжити.`
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
}

// ---------- Екрани ----------

const SCREENS = ["onboarding", "home", "settings", "region", "sounds"];
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
    } else {
      show("home");
    }
  }));

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
if (settings.onboarded) {
  show("home");
} else {
  goStep(0);
  show("onboarding");
}
setInterval(() => { if (watch.phase !== "IDLE") render(); }, 10_000);

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
