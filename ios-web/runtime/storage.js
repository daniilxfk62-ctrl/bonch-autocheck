const DB_NAME = 'pulse_ios_db';
const DB_VERSION = 1;
const STORE_NAME = 'kv';

const DEFAULTS = {
  profile: { loggedIn: false, fio: '', fullName: '', name: '', group: '', email: '' },
  config: { autoDelay: 'random', antiKick: true, themeMode: 'light', daemonEnabled: false },
  smartRules: {},
  catalog: [],
  semesterDays: [],
  messages: [],
  groupFiles: [],
  diary: [],
  logs: [],
  mtimes: { profile: 0, cat: 0, days: 0, messages: 0, files: 0, diary: 0, lastSyncAt: 0 },
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

export async function initStorage() {
  Object.keys(DEFAULTS).forEach((k) => {
    if (!memoryCache.has(k)) memoryCache.set(k, clone(DEFAULTS[k]));
  });

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
  const now = Date.now();
  setSync('mtimes', { profile: now, cat: now, days: now, messages: now, files: now, diary: now, lastSyncAt: 0 });
}