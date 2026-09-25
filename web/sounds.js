import { icon } from "./icons.js";

// Звуки синтезує tools/make_sounds.py.
export const SOUNDS = [
  { id: "marimba", name: "Маримба", desc: "Бадьора мелодія" },
  { id: "sunrise", name: "Світанок", desc: "М'які дзвіночки" },
  { id: "harp", name: "Арфа", desc: "Спокійне глісандо" },
  { id: "birds", name: "Пташки", desc: "Ранковий щебет" },
  { id: "pulse", name: "Хвилі", desc: "Низькі наростаючі тони" },
  { id: "classic", name: "Класичний", desc: "Електронний сигнал" },
  { id: "bells", name: "Дзвоники", desc: "Механічний будильник, найгучніший" },
];
export const DEFAULT_SOUND = "marimba";
export const CUSTOM = "custom";
const MAX_FILE_BYTES = 20 * 1024 * 1024;

// ---------- Власний файл в IndexedDB ----------

function db() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("vidbiy", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("files");
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function idb(mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction("files", mode);
    const req = fn(tx.objectStore("files"));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = () => reject(tx.error);
  });
}

export const loadCustom = () => idb("readonly", (s) => s.get(CUSTOM)).catch(() => null);
const saveCustom = (value) => idb("readwrite", (s) => s.put(value, CUSTOM));

let objectUrl = null;

/** Адреса звуку для програвання. Якщо власного файлу немає, повертає стандартний. */
export async function soundUrl(id) {
  if (id === CUSTOM) {
    const custom = await loadCustom();
    if (custom?.blob) {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = URL.createObjectURL(custom.blob);
      return objectUrl;
    }
    id = DEFAULT_SOUND;
  }
  return `sounds/${id}.mp3`;
}

export function soundName(id, customName) {
  if (id === CUSTOM) return customName ? `Свій: ${customName}` : "Свій звук";
  return SOUNDS.find((s) => s.id === id)?.name ?? SOUNDS[0].name;
}

// Перевіряємо, що браузер справді вміє відтворити файл, до того як на нього покластися вночі.
function canPlay(blob) {
  return new Promise((resolve) => {
    const a = new Audio();
    const url = URL.createObjectURL(blob);
    const done = (ok) => { URL.revokeObjectURL(url); resolve(ok); };
    const timer = setTimeout(() => done(false), 8000);
    a.addEventListener("loadedmetadata", () => { clearTimeout(timer); done(true); }, { once: true });
    a.addEventListener("error", () => { clearTimeout(timer); done(false); }, { once: true });
    a.preload = "metadata";
    a.src = url;
  });
}

// ---------- Прослуховування ----------

const preview = new Audio();
let previewing = null;
let previewTimer = 0;
const listeners = new Set();

export async function togglePreview(id) {
  if (previewing === id) return stopPreview();
  stopPreview();
  previewing = id;
  listeners.forEach((f) => f());
  preview.src = await soundUrl(id);
  preview.currentTime = 0;
  preview.play().catch(() => stopPreview());
  previewTimer = setTimeout(stopPreview, 6000);
}

export function stopPreview() {
  clearTimeout(previewTimer);
  preview.pause();
  previewing = null;
  listeners.forEach((f) => f());
}

// ---------- Список звуків ----------

/**
 * Список вибору звуку з прослуховуванням і завантаженням свого файлу.
 * [settings] — об'єкт з полями sound і customName, [onChange] викликається після зміни.
 */
export function mountSoundList(root, { settings, onChange, onError }) {
  const render = async () => {
    const custom = await loadCustom();
    const rows = SOUNDS.map((s) => ({ ...s }));
    if (custom?.blob) rows.push({ id: CUSTOM, name: "Свій звук", desc: custom.name });
    root.innerHTML = `
      <div class="group">
        ${rows.map((s) => `
          <button class="row sound-row" data-id="${s.id}">
            <span class="badge play">${icon(previewing === s.id ? "stop" : "play")}</span>
            <span><b>${s.name}</b><small>${escapeHtml(s.desc)}</small></span>
            ${settings.sound === s.id ? `<span class="check">${icon("check")}</span>` : ""}
          </button>`).join("")}
      </div>
      <label class="btn tonal upload">
        ${icon("upload")}${custom?.blob ? "Замінити свій файл" : "Обрати свій файл"}
        <input type="file" accept="audio/*" hidden>
      </label>`;
  };

  root.addEventListener("click", (e) => {
    const row = e.target.closest(".sound-row");
    if (!row) return;
    const id = row.dataset.id;
    if (settings.sound !== id) {
      settings.sound = id;
      onChange();
    }
    togglePreview(id);
  });

  root.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (file.size > MAX_FILE_BYTES) return onError("Файл завеликий: до 20 МБ");
    if (!(await canPlay(file))) return onError("Не вдалося відтворити цей файл. Спробуйте MP3 або M4A");
    try {
      await saveCustom({ blob: file, name: file.name });
    } catch {
      return onError("Не вдалося зберегти файл у браузері");
    }
    settings.sound = CUSTOM;
    settings.customName = file.name;
    onChange();
    await render();
    togglePreview(CUSTOM);
  });

  listeners.add(render);
  render();
  return { render };
}

const escapeHtml = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
