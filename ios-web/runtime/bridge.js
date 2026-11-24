import { initStorage, getSync, setSync, touchMtime, appendLog, clearAccountData } from './storage.js';
import { importFromHash, importFromClipboard } from './ipc.js';

let remoteConfig = {
  appVersion: '02.09.00',
  shortcutName: 'Pulse AutoCheck',
  shortcutIcloudUrl: '',
  ipcPrefix: 'PULSE_SYNC_V1:',
  repos: ['daniilxfk62-ctrl/bonch-autocheck', 'g1tm4estr0/bonch-autocheck']
};

let runtimeStatus = 'Готов к синхронизации через Команды';
let isCheckingUpdate = false;

function compareVersions(a, b) {
  const pa = String(a || '').replace(/^v/i, '').split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b || '').replace(/^v/i, '').split('.').map((n) => parseInt(n, 10) || 0);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const da = pa[i] || 0;
    const db = pb[i] || 0;
    if (da !== db) return da - db;
  }
  return 0;
}

async function loadRemoteConfig() {
  try {
    const res = await fetch('./config.json', { cache: 'no-store' });
    if (res.ok) {
      const cfg = await res.json();
      remoteConfig = Object.assign({}, remoteConfig, cfg);
    }
  } catch (err) {
    appendLog('Используется локальный конфиг (офлайн)');
  }
}

async function checkDualRepoUpdates() {
  if (isCheckingUpdate) return;
  isCheckingUpdate = true;
  const repos = Array.isArray(remoteConfig.repos) && remoteConfig.repos.length
    ? remoteConfig.repos
    : ['daniilxfk62-ctrl/bonch-autocheck', 'g1tm4estr0/bonch-autocheck'];
  const currentVersion = remoteConfig.appVersion || '02.09.00';

  let best = null;
  for (let i = 0; i < repos.length; i++) {
    const repo = repos[i];
    try {
      const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
        headers: { Accept: 'application/vnd.github+json' }
      });
      if (!res.ok) continue;
      const data = await res.json();
      const tag = String(data.tag_name || '').replace(/^v/i, '').trim();
      if (!tag) continue;
      if (!best || compareVersions(tag, best.latestVersion) > 0) {
        best = {
          hasUpdate: compareVersions(tag, currentVersion) > 0,
          currentVersion,
          latestVersion: tag,
          sourceRepo: repo,
          isFallback: i > 0,
          releaseNotes: data.body || '',
          releaseUrl: data.html_url || `https://github.com/${repo}/releases`,
          apkUrl: '',
          apkName: '',
          checkedAt: Date.now()
        };
      }
    } catch (e) {}
  }

  if (best) {
    setSync('updateInfo', best);
    appendLog(`Проверка обновлений: v${best.latestVersion} (${best.sourceRepo})`);
    triggerUiRefresh();
  }
  isCheckingUpdate = false;
}

function triggerUiRefresh() {
  if (typeof window.__pulseForceSync === 'function') {
    try { window.__pulseForceSync(); } catch (e) {}
  }
}

function launchShortcut(mode, extra = {}) {
  const name = encodeURIComponent(remoteConfig.shortcutName || 'Pulse AutoCheck');
  const payload = encodeURIComponent(JSON.stringify(Object.assign({ mode }, extra)));
  runtimeStatus = 'Запуск Apple-команды… После завершения вернитесь в Pulse и нажмите «Принять данные»';
  appendLog(`Запуск Apple-команды (mode=${mode})`);
  triggerUiRefresh();
  window.location.href = `shortcuts://run-shortcut?name=${name}&input=text&text=${payload}`;
}

function buildBridgeObject() {
  return {
    __isIOS: true,
    __pending: false,

    getState() {
      const cfg = getSync('config', {});
      const mtimes = getSync('mtimes', {});
      const prof = getSync('profile', {});
      return JSON.stringify({
        daemonEnabled: !!cfg.daemonEnabled,
        status: runtimeStatus,
        scanningSemester: false,
        scanStatus: '',
        logs: getSync('logs', []),
        authState: prof.loggedIn ? 'ok' : '',
        authError: '',
        profileMtime: mtimes.profile || 0,
        catMtime: mtimes.cat || 0,
        daysMtime: mtimes.days || 0,
        messagesMtime: mtimes.messages || 0,
        filesMtime: mtimes.files || 0,
        diaryMtime: mtimes.diary || 0
      });
    },

    getSavedConfig() {
      const cfg = getSync('config', {});
      return JSON.stringify({
        autoDelay: cfg.autoDelay || 'random',
        antiKick: typeof cfg.antiKick === 'boolean' ? cfg.antiKick : true,
        themeMode: cfg.themeMode || 'light'
      });
    },

    getSmartRules() {
      return JSON.stringify(getSync('smartRules', {}));
    },

    saveSmartRules(jsonStr) {
      try {
        setSync('smartRules', JSON.parse(jsonStr || '{}'));
        appendLog('Правила автоотметки сохранены локально');
      } catch (e) {}
    },

    getProfile() {
      return JSON.stringify(getSync('profile', { loggedIn: false }));
    },

    getCachedSmartCatalog() {
      return JSON.stringify(getSync('catalog', []));
    },

    getCachedSemesterDays() {
      return JSON.stringify(getSync('semesterDays', []));
    },

    getCachedMessages() {
      return JSON.stringify(getSync('messages', []));
    },

    getCachedGroupFiles() {
      return JSON.stringify(getSync('groupFiles', []));
    },

    getCachedDiarySummary() {
      return JSON.stringify(getSync('diary', []));
    },

    getUpdateInfo() {
      const info = getSync('updateInfo', null);
      return info ? JSON.stringify(info) : '';
    },

    checkForUpdatesNow() {
      checkDualRepoUpdates();
    },

    downloadAndInstallUpdate(_apkUrl, _apkName, releaseUrl) {
      const target = remoteConfig.shortcutIcloudUrl || releaseUrl || './setup.html';
      window.open(target, '_blank', 'noopener,noreferrer');
      return true;
    },

    openExternalUrl(url) {
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
    },

    setAutoCheckEnabled(enabled) {
      const cfg = Object.assign({}, getSync('config', {}), { daemonEnabled: !!enabled });
      setSync('config', cfg);
      runtimeStatus = enabled ? 'Управляется автоматизацией iOS' : 'Приостановлено';
      appendLog(`Автоотметка iOS: ${enabled ? 'ON' : 'OFF'}`);
    },

    savePulseSettings(autoDelay, antiKick, themeMode) {
      const cfg = Object.assign({}, getSync('config', {}), {
        autoDelay: autoDelay || 'random',
        antiKick: !!antiKick,
        themeMode: themeMode || 'light'
      });
      setSync('config', cfg);
    },

    manualCheckIn(raspId, weekId, start, subject) {
      launchShortcut('checkin', { raspId, weekId, start, subject });
    },

    scanSemesterNow() {
      launchShortcut('sync', { scope: 'schedule' });
    },

    syncAll() {
      launchShortcut('sync', { scope: 'all' });
    },

    syncMessagesNow() {
      launchShortcut('sync', { scope: 'messages' });
    },

    checkDiaryNow() {
      launchShortcut('sync', { scope: 'diary' });
    },

    markMessageRead(msgId) {
      const list = getSync('messages', []).slice();
      const idx = list.findIndex((m) => m.id === msgId);
      if (idx >= 0) {
        list[idx] = Object.assign({}, list[idx], { unread: false });
        setSync('messages', list);
        touchMtime('messages');
      }
    },

    toggleSenderMute(msgId) {
      const list = getSync('messages', []).slice();
      const idx = list.findIndex((m) => m.id === msgId);
      if (idx < 0) return false;
      const next = !list[idx].muted;
      list[idx] = Object.assign({}, list[idx], { muted: next });
      setSync('messages', list);
      touchMtime('messages');
      return next;
    },

    sendMessage(msgId, text) {
      launchShortcut('reply', { msgId, text });
    },

    sendMessageWithFile(msgId, text) {
      launchShortcut('reply', { msgId, text });
    },

    downloadGroupFile(url) {
      if (!url) return false;
      window.open(url, '_blank', 'noopener,noreferrer');
      return true;
    },

    loginAccount() {
      window.location.href = './setup.html';
      return true;
    },

    logoutAccount() {
      clearAccountData();
      runtimeStatus = 'Выход выполнен';
      appendLog('Локальный кэш iOS очищен');
      triggerUiRefresh();
    },

    testNotifNow() {
      launchShortcut('test_notif');
    },

    // iOS-специфичные методы для UI
    async importFromClipboard() {
      const res = await importFromClipboard(remoteConfig.ipcPrefix);
      if (res.ok) {
        runtimeStatus = 'Синхронизировано с ЛК';
        triggerUiRefresh();
      }
      return res;
    },

    getIosMeta() {
      const mtimes = getSync('mtimes', {});
      const isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
      return JSON.stringify({
        lastSyncAt: mtimes.lastSyncAt || 0,
        shortcutName: remoteConfig.shortcutName,
        shortcutIcloudUrl: remoteConfig.shortcutIcloudUrl,
        isStandalone
      });
    }
  };
}

async function bootstrap() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./pwa/sw.js', { scope: './' }).catch(() => {});
  }

  await Promise.all([initStorage(), loadRemoteConfig()]);
  window.AndroidBridge = buildBridgeObject();

  try {
    const hashRes = await importFromHash(remoteConfig.ipcPrefix);
    if (hashRes && hashRes.ok) {
      runtimeStatus = 'Синхронизировано с ЛК';
    }
  } catch (err) {
    appendLog(`Ошибка импорта #p=: ${err.message}`);
  }

  triggerUiRefresh();
  checkDualRepoUpdates();
}

bootstrap();