import { icon } from "./icons.js";

export const PROXY = "https://vidbiy-proxy.ishawyha.workers.dev";

// Області: sirenId — regionId у siren.pp.ua, ubilling — ключ у відповіді ubilling (null — не підтримується).
const OBLASTS = [
  ["31", "м. Київ"],
  ["9999", "Автономна Республіка Крим", null],
  ["4", "Вінницька область"],
  ["8", "Волинська область"],
  ["9", "Дніпропетровська область"],
  ["28", "Донецька область"],
  ["10", "Житомирська область"],
  ["11", "Закарпатська область"],
  ["12", "Запорізька область"],
  ["13", "Івано-Франківська область"],
  ["14", "Київська область"],
  ["15", "Кіровоградська область"],
  ["16", "Луганська область"],
  ["27", "Львівська область"],
  ["17", "Миколаївська область"],
  ["18", "Одеська область"],
  ["19", "Полтавська область"],
  ["5", "Рівненська область"],
  [null, "м. Севастополь", "Севастополь"],
  ["20", "Сумська область"],
  ["21", "Тернопільська область"],
  ["22", "Харківська область"],
  ["23", "Херсонська область"],
  ["3", "Хмельницька область"],
  ["24", "Черкаська область"],
  ["26", "Чернівецька область"],
  ["25", "Чернігівська область"],
].map(([sirenId, name, ubilling]) => ({ sirenId, name, ubilling: ubilling === undefined ? name : ubilling, detail: null }));

export const DEFAULT_REGION = OBLASTS[0];
const TEST_REGION_ID = "0";
const TREE_KEY = "vidbiy.tree";
const TREE_MAX_AGE = 7 * 24 * 3600 * 1000;
const collator = new Intl.Collator("uk");

export async function getJSON(path, timeoutMs = 10000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(PROXY + path, { cache: "no-store", signal: ctrl.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

// Дерево «область → район → громада». Зберігається в localStorage і оновлюється раз на тиждень.
let tree = null;

function buildTree(states) {
  const places = new Map();
  const walk = (node, parentId) => {
    const childIds = (node.regionChildIds || []).map((c) => walk(c, node.regionId));
    places.set(node.regionId, { id: node.regionId, name: node.regionName, parentId, childIds });
    return node.regionId;
  };
  states.forEach((s) => walk(s, null));
  places.delete(TEST_REGION_ID);
  return places;
}

function readCachedTree() {
  try {
    const saved = JSON.parse(localStorage.getItem(TREE_KEY));
    if (saved?.states) return { places: buildTree(saved.states), savedAt: saved.savedAt };
  } catch {}
  return null;
}

export async function getTree() {
  if (tree && Date.now() - tree.savedAt < TREE_MAX_AGE) return tree.places;
  if (!tree) tree = readCachedTree();
  if (!tree || Date.now() - tree.savedAt >= TREE_MAX_AGE) {
    try {
      const { states } = await getJSON("/regions", 20000);
      tree = { places: buildTree(states), savedAt: Date.now() };
      try { localStorage.setItem(TREE_KEY, JSON.stringify({ states, savedAt: tree.savedAt })); } catch {}
    } catch (e) {
      if (!tree) throw e;
    }
  }
  return tree.places;
}

export function ancestors(places, id) {
  const out = [];
  for (let p = places.get(id)?.parentId; p; p = places.get(p)?.parentId) out.push(p);
  return out;
}

export function descendants(places, id) {
  const out = new Set();
  const walk = (pid) => places.get(pid)?.childIds.forEach((c) => { out.add(c); walk(c); });
  walk(id);
  return out;
}

const pathOf = (places, id) => ancestors(places, id).map((a) => places.get(a)?.name).filter(Boolean).join(", ");

function regionFor(places, id) {
  const oblast = OBLASTS.find((o) => o.sirenId === id);
  if (oblast) return oblast;
  return { sirenId: id, name: places.get(id)?.name ?? "", ubilling: null, detail: pathOf(places, id) || null };
}

const sameRegion = (a, b) => a.sirenId === b.sirenId && a.name === b.name;
const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

/** Вибір області, району чи громади з пошуком. Рендериться в [root]. */
export function mountPicker(root, { getSelected, onPick }) {
  root.innerHTML = `
    <label class="search"><span data-icon="search">${icon("search")}</span>
      <input type="search" placeholder="Пошук: місто, громада, район" autocomplete="off" enterkeyhint="search">
      <button class="icon-btn clear" aria-label="Очистити" hidden>${icon("close")}</button>
    </label>
    <button class="crumbs" hidden></button>
    <div class="picker-note" hidden></div>
    <div class="list" role="list"></div>`;
  const input = root.querySelector("input");
  const clear = root.querySelector(".clear");
  const crumbs = root.querySelector(".crumbs");
  const note = root.querySelector(".picker-note");
  const list = root.querySelector(".list");
  let stack = [];
  let places = null;
  let failed = false;
  let items = [];

  getTree().then((p) => { places = p; render(); }).catch(() => { failed = true; render(); });

  function render() {
    const q = input.value.trim().toLowerCase();
    clear.hidden = !input.value;
    const selected = getSelected();

    if (q.length >= 2) {
      const fromTree = places
        ? [...places.values()]
            .filter((p) => p.name.toLowerCase().includes(q))
            .sort((a, b) => ancestors(places, a.id).length - ancestors(places, b.id).length || collator.compare(a.name, b.name))
            .map((p) => ({ region: regionFor(places, p.id), sub: pathOf(places, p.id) || null }))
        : [];
      const staticOnly = OBLASTS.filter((o) => !o.sirenId && o.name.toLowerCase().includes(q)).map((o) => ({ region: o }));
      items = [...staticOnly, ...fromTree].slice(0, 150);
    } else if (!stack.length || !places) {
      items = OBLASTS.map((o) => ({
        region: o,
        open: places && o.sirenId && places.get(o.sirenId)?.childIds.length ? o.sirenId : null,
      }));
    } else {
      const current = stack.at(-1);
      items = [
        { region: regionFor(places, current), sub: stack.length === 1 ? "Уся область" : "Увесь район" },
        ...places.get(current).childIds
          .map((id) => places.get(id))
          .sort((a, b) => collator.compare(a.name, b.name))
          .map((p) => ({ region: regionFor(places, p.id), open: p.childIds.length ? p.id : null })),
      ];
    }

    crumbs.hidden = !(q.length < 2 && stack.length && places);
    if (!crumbs.hidden) crumbs.innerHTML = icon("left") + esc(stack.map((id) => places.get(id)?.name).join(" › "));

    note.hidden = true;
    if (failed && !places) {
      note.hidden = false;
      note.textContent = "Не вдалося завантажити райони й громади. Області доступні.";
    } else if (q.length >= 2 && !places) {
      note.hidden = false;
      note.textContent = "Завантаження списку громад…";
    } else if (q.length >= 2 && !items.length) {
      note.hidden = false;
      note.textContent = "Нічого не знайдено";
    }

    list.innerHTML = items
      .map((it, i) => `
        <button class="item" data-i="${i}" role="listitem">
          <span>${esc(it.region.name)}${it.sub ? `<small>${esc(it.sub)}</small>` : ""}</span>
          ${sameRegion(it.region, selected) ? `<span class="check">${icon("check")}</span>` : ""}
          ${it.open ? `<span class="dim">${icon("right")}</span>` : ""}
        </button>`)
      .join("");
  }

  list.addEventListener("click", (e) => {
    const btn = e.target.closest(".item");
    if (!btn) return;
    const it = items[+btn.dataset.i];
    if (it.open) {
      stack.push(it.open);
      render();
      list.scrollTop = 0;
    } else {
      onPick(it.region);
    }
  });
  crumbs.addEventListener("click", () => { stack.pop(); render(); });
  input.addEventListener("input", render);
  clear.addEventListener("click", () => { input.value = ""; render(); input.focus(); });

  render();
  return {
    reset() { stack = []; input.value = ""; render(); list.scrollTop = 0; },
    /** true, якщо крок назад оброблено всередині вибору. */
    back() {
      if (!stack.length) return false;
      stack.pop();
      render();
      return true;
    },
  };
}
