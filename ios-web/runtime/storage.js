const DB_NAME = 'pulse_ios_db';
const DB_VERSION = 1;
const STORE_NAME = 'kv';
const LS_DAY_NOTES_KEY = 'pulse_ios_day_notes_v1';

const DEFAULTS = {
  profile: { loggedIn: false, fio: '', fullName: '', name: '', group: '', email: '' },
  config: { autoDelay: 'random', antiKick: true, themeMode: 'light', daemonEnabled: false },
  smartRules: {},
  groupChoices: {},       // { "Предмет": ["Преподаватель 1", "Преподаватель 2"] }
  dayNotes: {},           // { "DD.MM.YYYY": [...] }
  messageTeachers: [],    // [{ id, name }]
  messageBodiesCache: {}, // { "<serverId>": rawBodyOrJson }
  serverWarning: '',
  catalog: [],
  semesterDays: [],
  messages: [],
  groupFiles: [],
  diary: [],
  logs: [],
  mtimes: { profile: 0, cat: 0, days: 0, messages: 0, files: 0, diary: 0, teachers: 0, lastSyncAt: 0 },
  updateInfo: null
};

let dbPromise = null;
const memoryCache = new Map();

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function clone(val) {
  return val === undefined ? undefined : JSON.parse(JSON.stringify(val));
}

export function normalizeGroupChoices(raw) {
  const out = {};
  if (!raw || typeof raw !== 'object') return out;
  Object.keys(raw).forEach((subj) => {
    const val = raw[subj];
    if (Array.isArray(val)) {
      const cleaned = val.map((s) => String(s || '').trim()).filter(Boolean);
      if (cleaned.length) out[subj] = Array.from(new Set(cleaned));
    } else if (typeof val === 'string' && val.trim()) {
      const parts = val.split(',').map((s) => s.trim()).filter(Boolean);
      if (parts.length) out[subj] = Array.from(new Set(parts));
    }
  });
  return out;
}

export async function initStorage() {
  Object.keys(DEFAULTS).forEach((k) => {
    if (!memoryCache.has(k)) memoryCache.set(k, clone(DEFAULTS[k]));
  });

  // Синхронно подхватываем dayNotes из localStorage на случай первого запуска
  try {
    const lsNotes = localStorage.getItem(LS_DAY_NOTES_KEY);
    if (lsNotes) {
      const parsed = JSON.parse(lsNotes);
      if (parsed && typeof parsed === 'object') memoryCache.set('dayNotes', parsed);
    }
  } catch (e) {}

  try {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.openCursor();
      req.onsuccess = (e) => {
        const cursor = e.target.result;
        if (cursor) {
          memoryCache.set(cursor.key, cursor.value);
          cursor.continue();
        } else {
          resolve();
        }
      };
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('[Pulse Storage] IndexedDB fallback to RAM:', err);
  }

  // Гарантируем формат string[] для groupChoices
  memoryCache.set('groupChoices', normalizeGroupChoices(memoryCache.get('groupChoices')));
}

export function getSync(key, fallback = null) {
  if (memoryCache.has(key)) {
    const val = memoryCache.get(key);
    return val !== undefined ? val : fallback;
  }
  return key in DEFAULTS ? clone(DEFAULTS[key]) : fallback;
}

export function setSync(key, value) {
  memoryCache.set(key, value);
  if (key === 'dayNotes') {
    try { localStorage.setItem(LS_DAY_NOTES_KEY, JSON.stringify(value || {})); } catch (e) {}
  }
  openDb()
    .then((db) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put(value, key);
    })
    .catch((err) => console.warn('[Pulse Storage] Write error:', key, err));
}

export function touchMtime(field) {
  const mtimes = Object.assign({}, getSync('mtimes', DEFAULTS.mtimes));
  const now = Date.now();
  mtimes[field] = now;
  mtimes.lastSyncAt = now;
  setSync('mtimes', mtimes);
  return mtimes;
}

export function appendLog(message) {
  const logs = Array.isArray(getSync('logs', [])) ? getSync('logs', []).slice() : [];
  const now = new Date();
  const stamp = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
  logs.push(`[${stamp}] ${message}`);
  if (logs.length > 200) logs.splice(0, logs.length - 200);
  setSync('logs', logs);
}

export function clearAccountData() {
  setSync('profile', clone(DEFAULTS.profile));
  setSync('catalog', []);
  setSync('semesterDays', []);
  setSync('messages', []);
  setSync('groupFiles', []);
  setSync('diary', []);
  setSync('messageTeachers', []);
  setSync('messageBodiesCache', {});
  setSync('serverWarning', '');
  const now = Date.now();
  setSync('mtimes', { profile: now, cat: now, days: now, messages: now, files: now, diary: now, teachers: now, lastSyncAt: 0 });
}