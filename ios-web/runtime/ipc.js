import { getSync, setSync, touchMtime, appendLog } from './storage.js';
import { parseScheduleHtml, buildSmartCatalog } from '../parsers/schedule.parser.js';
import { parseDiaryHtml } from '../parsers/diary.parser.js';
import {
  parseMessagesHtml,
  parseGroupFilesHtml,
  parseMessageTeachersHtml
} from '../parsers/messages.parser.js';

const DEFAULT_PREFIX = 'PULSE_SYNC_V1:';

function base64ToBytes(b64) {
  const normalized = b64.replace(/-/g, '+').replace(/_/g, '/').replace(/\s+/g, '');
  const padLen = (4 - (normalized.length % 4)) % 4;
  const padded = normalized + '='.repeat(padLen);
  const bin = atob(padded);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    bytes[i] = bin.charCodeAt(i);
  }
  return bytes;
}

async function decodePayloadString(rawText, prefix = DEFAULT_PREFIX) {
  const trimmed = String(rawText || '').trim();
  if (!trimmed) return null;

  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    return JSON.parse(trimmed);
  }

  let encoded = trimmed;
  if (encoded.startsWith(prefix)) {
    encoded = encoded.slice(prefix.length).trim();
  } else if (encoded.startsWith(DEFAULT_PREFIX)) {
    encoded = encoded.slice(DEFAULT_PREFIX.length).trim();
  } else {
    return null;
  }

  const bytes = base64ToBytes(encoded);
  const isGzip = bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (isGzip && typeof DecompressionStream !== 'undefined') {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    const text = await new Response(stream).text();
    return JSON.parse(text);
  }

  const text = new TextDecoder('utf-8').decode(bytes);
  return JSON.parse(text);
}

export function applySyncPayload(payload) {
  if (!payload || typeof payload !== 'object') {
    throw new Error('Пустой или повреждённый пакет синхронизации');
  }

  const updated = [];

  // Предупреждение о медленном ответе или ошибке lk.sut.ru
  if (typeof payload.serverWarning === 'string') {
    setSync('serverWarning', payload.serverWarning);
  } else {
    setSync('serverWarning', '');
  }

  if (payload.profile && typeof payload.profile === 'object') {
    const prev = getSync('profile', {});
    const merged = {
      loggedIn: payload.profile.loggedIn !== false,
      fio: payload.profile.fio || payload.profile.name || prev.fio || '',
      fullName: payload.profile.fullName || payload.profile.fio || prev.fullName || '',
      name: payload.profile.name || payload.profile.fio || prev.name || 'Аккаунт ЛК СПбГУТ',
      group: payload.profile.group || prev.group || '',
      email: payload.profile.email || prev.email || ''
    };
    setSync('profile', merged);
    touchMtime('profile');
    updated.push('профиль');
  }

  const html = payload.html || {};
  const checkins = Array.isArray(payload.checkins) ? payload.checkins : [];
  const groupChoices = getSync('groupChoices', {});

  if (html.schedule || (Array.isArray(html.scheduleWeeks) && html.scheduleWeeks.length)) {
    const days = parseScheduleHtml(html.schedule || '', html.scheduleWeeks || [], checkins, groupChoices);
    if (days.length) {
      setSync('semesterDays', days);
      touchMtime('days');
      const catalog = buildSmartCatalog(days);
      setSync('catalog', catalog);
      touchMtime('cat');
      updated.push(`расписание (${days.length} дн.)`);
    }
  } else if (Array.isArray(payload.semesterDays)) {
    setSync('semesterDays', payload.semesterDays);
    touchMtime('days');
    setSync('catalog', buildSmartCatalog(payload.semesterDays));
    touchMtime('cat');
    updated.push(`расписание (${payload.semesterDays.length} дн.)`);
  }

  if (html.diary) {
    const diary = parseDiaryHtml(html.diary);
    setSync('diary', diary);
    touchMtime('diary');
    updated.push(`дневник (${diary.length})`);
  } else if (Array.isArray(payload.diary)) {
    setSync('diary', payload.diary);
    touchMtime('diary');
    updated.push(`дневник (${payload.diary.length})`);
  }

  // Объединяем кэш уже загруженных тел сообщений (sendto2.php) с новыми из Shortcut
  const bodiesCache = Object.assign({}, getSync('messageBodiesCache', {}));
  let incomingBodies = html.messageBodies;
  if (typeof incomingBodies === 'string' && incomingBodies.trim().startsWith('{')) {
    try { incomingBodies = JSON.parse(incomingBodies); } catch (e) { incomingBodies = null; }
  }
  if (incomingBodies && typeof incomingBodies === 'object') {
    Object.keys(incomingBodies).forEach((id) => {
      if (incomingBodies[id]) bodiesCache[String(id)] = incomingBodies[id];
    });
    setSync('messageBodiesCache', bodiesCache);
  }

  if (html.messages) {
    const prevMsgs = getSync('messages', []);
    const messages = parseMessagesHtml(html.messages, bodiesCache, prevMsgs);
    setSync('messages', messages);
    touchMtime('messages');
    updated.push(`сообщения (${messages.length})`);
  } else if (Array.isArray(payload.messages)) {
    setSync('messages', payload.messages);
    touchMtime('messages');
    updated.push(`сообщения (${payload.messages.length})`);
  }

  // Список преподавателей из message_create_stud.php (<select name="adresat">)
  if (html.messageCreate) {
    const teachers = parseMessageTeachersHtml(html.messageCreate);
    if (teachers.length) {
      setSync('messageTeachers', teachers);
      touchMtime('teachers');
      updated.push(`преподаватели (${teachers.length})`);
    }
  } else if (Array.isArray(payload.messageTeachers)) {
    setSync('messageTeachers', payload.messageTeachers);
    touchMtime('teachers');
    updated.push(`преподаватели (${payload.messageTeachers.length})`);
  }

  if (html.groupFiles) {
    const files = parseGroupFilesHtml(html.groupFiles);
    setSync('groupFiles', files);
    touchMtime('files');
    updated.push(`файлы (${files.length})`);
  } else if (Array.isArray(payload.groupFiles)) {
    setSync('groupFiles', payload.groupFiles);
    touchMtime('files');
    updated.push(`файлы (${payload.groupFiles.length})`);
  }

  if (Array.isArray(payload.logs) && payload.logs.length) {
    payload.logs.slice(-30).forEach((line) => appendLog(`[Shortcut] ${line}`));
  }

  if (typeof payload.daemonEnabled === 'boolean') {
    const cfg = Object.assign({}, getSync('config', {}), { daemonEnabled: payload.daemonEnabled });
    setSync('config', cfg);
  }

  if (updated.length && !getSync('profile', {}).loggedIn) {
    const prev = getSync('profile', {});
    setSync('profile', Object.assign({}, prev, { loggedIn: true, name: prev.name || 'Аккаунт ЛК СПбГУТ' }));
    touchMtime('profile');
  }

  appendLog(`Импорт завершён: ${updated.join(', ') || 'без изменений'}`);
  return { ok: true, updated };
}

export async function importFromHash(prefix = DEFAULT_PREFIX) {
  const hash = window.location.hash || '';
  if (!hash.startsWith('#p=')) return { ok: false, skipped: true };

  const rawParam = decodeURIComponent(hash.slice(3));
  try {
    history.replaceState(null, '', window.location.pathname + window.location.search);
  } catch (e) {}

  const payload = await decodePayloadString(`${prefix}${rawParam}`, prefix);
  if (!payload) return { ok: false, reason: 'Неверный формат #p=' };
  return applySyncPayload(payload);
}

export async function importFromClipboard(prefix = DEFAULT_PREFIX) {
  if (!navigator.clipboard || typeof navigator.clipboard.readText !== 'function') {
    return { ok: false, reason: 'Буфер обмена недоступен в этом браузере' };
  }

  let clipText = '';
  try {
    clipText = await navigator.clipboard.readText();
  } catch (err) {
    return { ok: false, reason: 'Разрешите вставку из буфера обмена в системном окне iOS' };
  }

  if (!clipText || (!clipText.trim().startsWith(prefix) && !clipText.trim().startsWith(DEFAULT_PREFIX))) {
    return { ok: false, reason: 'В буфере нет пакета от команды Pulse. Сначала запустите синхронизацию.' };
  }

  const payload = await decodePayloadString(clipText, prefix);
  if (!payload) {
    return { ok: false, reason: 'Не удалось распаковать пакет синхронизации' };
  }

  const res = applySyncPayload(payload);

  try {
    if (navigator.clipboard.writeText) {
      await navigator.clipboard.writeText('');
    }
  } catch (e) {}

  return res;
}