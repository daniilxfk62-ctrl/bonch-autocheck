const LK_BASE = 'https://lk.sut.ru';
const SENDTO_TEXT_FIELDS = ['annotation', 'text', 'message', 'mes', 'body', 'content', 'otvet'];

function cleanText(el) {
  return (el ? el.textContent || '' : '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
}

function cleanSenderName(raw) {
  return String(raw || '')
    .replace(/\(\s*(?:сотрудник\s*\/\s*преподаватель|преподаватель\s*\/\s*сотрудник|сотрудник|преподаватель)\s*\)/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Приводит даты вида "25-11-2026 19:51:54" или "25-11-2026" к "25.11.2026 19:51" / "25.11.2026"
 */
function normalizeDateDashToDot(raw) {
  const s = String(raw || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  if (!s) return { full: '', dateOnly: '', time: '' };

  const mDate = s.match(/(\d{2})[-.](\d{2})[-.](\d{4})/);
  const mTime = s.match(/(\d{1,2}:\d{2})(?::\d{2})?/);
  const dateOnly = mDate ? `${mDate[1]}.${mDate[2]}.${mDate[3]}` : '';
  const time = mTime ? mTime[1] : '';

  if (dateOnly && time) return { full: `${dateOnly} ${time}`, dateOnly, time };
  if (dateOnly) return { full: dateOnly, dateOnly, time: '' };
  return { full: s, dateOnly: '', time };
}

function resolveUrl(href) {
  if (!href) return '';
  if (/^https?:\/\//i.test(href)) return href;
  if (href.startsWith('//')) return `https:${href}`;
  if (href.startsWith('/')) return `${LK_BASE}${href}`;
  return `${LK_BASE}/cabinet/project/cabinet/forms/${href.replace(/^\.?\//, '')}`;
}

function getInitials(name) {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return 'ЛК';
  return words.slice(0, 2).map((w) => w[0]).join('').toUpperCase();
}

function extractAttachments(rootEl) {
  if (!rootEl) return [];
  const links = Array.from(rootEl.querySelectorAll('a[href]'));
  const result = [];
  const seen = new Set();
  links.forEach((a) => {
    const href = a.getAttribute('href') || '';
    if (!href.includes('subconto/sendto') && !a.hasAttribute('download')) return;
    const url = resolveUrl(href);
    if (seen.has(url)) return;
    seen.add(url);
    const name = cleanText(a) || decodeURIComponent(url.split('/').pop() || 'Файл');
    result.push({ name, url });
  });
  return result;
}

function htmlFragmentToTextAndAttachments(fragment) {
  if (!fragment) return { text: '', attachments: [] };
  const doc = new DOMParser().parseFromString(String(fragment), 'text/html');
  const attachments = extractAttachments(doc.body);
  doc.querySelectorAll('a[href*="subconto/sendto"]').forEach((a) => a.remove());
  const text = (doc.body ? doc.body.innerText || doc.body.textContent || '' : '')
    .replace(/\u00a0/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text, attachments };
}

/**
 * Разбирает ответ sendto2.php?id=<ID>&prosmotr (JSON со всеми текстовыми полями или HTML)
 */
function parseSendtoResponse(rawBody) {
  if (!rawBody) return { text: '', attachments: [] };

  let obj = null;
  if (typeof rawBody === 'object') {
    obj = rawBody;
  } else {
    const trimmed = String(rawBody).trim();
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
      try { obj = JSON.parse(trimmed); } catch (e) { obj = null; }
    }
  }

  if (obj && typeof obj === 'object') {
    const textParts = [];
    const attachments = [];
    const seenUrls = new Set();

    SENDTO_TEXT_FIELDS.forEach((field) => {
      const val = obj[field];
      if (typeof val !== 'string' || !val.trim()) return;
      const parsed = htmlFragmentToTextAndAttachments(val);
      if (parsed.text && !textParts.includes(parsed.text)) {
        textParts.push(parsed.text);
      }
      parsed.attachments.forEach((att) => {
        if (!seenUrls.has(att.url)) {
          seenUrls.add(att.url);
          attachments.push(att);
        }
      });
    });

    // Поддержка отдельных полей файлов, если сервер их вернул
    const extraFiles = obj.files || obj.attachments || obj.file;
    if (typeof extraFiles === 'string' && extraFiles.trim()) {
      htmlFragmentToTextAndAttachments(extraFiles).attachments.forEach((att) => {
        if (!seenUrls.has(att.url)) {
          seenUrls.add(att.url);
          attachments.push(att);
        }
      });
    }

    return { text: textParts.join('\n\n').trim(), attachments };
  }

  return htmlFragmentToTextAndAttachments(String(rawBody));
}

export function parseMessagesHtml(html, messageBodies = {}, prevMessages = []) {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');

  const prevBySender = new Map();
  (Array.isArray(prevMessages) ? prevMessages : []).forEach((m) => {
    if (m && m.sender) prevBySender.set(m.sender, m);
  });

  const rows = Array.from(doc.querySelectorAll('tr[id^="tr_"]'));
  const threadsBySender = new Map();

  rows.forEach((tr) => {
    const idMatch = (tr.id || '').match(/^tr_(\d+)/);
    if (!idMatch) return;
    const msgId = idMatch[1];
    const tds = Array.from(tr.querySelectorAll('td'));
    if (tds.length < 3) return;

    const dateInfo = normalizeDateDashToDot(cleanText(tds[0]));
    const subject = cleanText(tds[1]);
    const sender = cleanSenderName(cleanText(tds[2])) || 'Личный кабинет';
    const caption = tds[3] ? cleanText(tds[3]) : '';
    const isUnreadRow = Boolean(tr.querySelector('b, strong')) || tr.classList.contains('unread');

    const bodyInfo = parseSendtoResponse(messageBodies[msgId] || '');
    const rowAttachments = extractAttachments(tr);
    const attachments = bodyInfo.attachments.length ? bodyInfo.attachments : rowAttachments;
    const bodyText = bodyInfo.text || subject || caption || 'Откройте сообщение в ЛК';

    if (!threadsBySender.has(sender)) {
      const prev = prevBySender.get(sender);
      threadsBySender.set(sender, {
        id: `sender_${msgId}`,
        sender,
        initials: getInitials(sender),
        role: 'Сообщение ЛК',
        time: dateInfo.time || dateInfo.dateOnly,
        unread: isUnreadRow,
        muted: prev ? !!prev.muted : false,
        thread: []
      });
    }

    const group = threadsBySender.get(sender);
    if (isUnreadRow) group.unread = true;
    group.thread.push({
      serverId: msgId,
      from: 'them',
      subject,
      text: bodyText,
      caption,
      date: dateInfo.full || dateInfo.dateOnly,
      dateOnly: dateInfo.dateOnly,
      time: dateInfo.time || dateInfo.dateOnly,
      attachments
    });
  });

  const result = Array.from(threadsBySender.values());
  result.forEach((g) => {
    g.thread.reverse();
    const last = g.thread[g.thread.length - 1];
    if (last) g.time = last.time || g.time;
  });
  return result;
}

/**
 * Парсинг списка преподавателей из message_create_stud.php (<select name="adresat">)
 */
export function parseMessageTeachersHtml(html) {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const select = doc.querySelector('select[name="adresat"]') || doc.querySelector('select#adresat');
  if (!select) return [];

  const seen = new Set();
  const list = [];
  Array.from(select.querySelectorAll('option')).forEach((opt) => {
    const id = String(opt.getAttribute('value') || '').trim();
    const name = cleanSenderName(cleanText(opt));
    if (!id || id === '0' || !name || seen.has(id)) return;
    seen.add(id);
    list.push({ id, name });
  });
  return list.sort((a, b) => a.name.localeCompare(b.name, 'ru'));
}

function shortHash(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) - h + str.charCodeAt(i)) | 0;
  }
  return Math.abs(h).toString(36);
}

export function parseGroupFilesHtml(html) {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const rows = Array.from(doc.querySelectorAll('tr[id^="tr_"]'));
  const files = [];

  rows.forEach((tr) => {
    const idMatch = (tr.id || '').match(/^tr_(\d+)/);
    const rowId = idMatch ? idMatch[1] : String(files.length);
    const tds = Array.from(tr.querySelectorAll('td'));
    if (tds.length < 3) return;

    const links = Array.from(tr.querySelectorAll('a[href*="subconto/sendto"], a[target="_blank"]'));
    if (!links.length) return;

    // Дата DD-MM-YYYY в files_group_pr.php находится в 3-й колонке (td[2]) -> DD.MM.YYYY
    const rawDateCol = tds[2] ? cleanText(tds[2]) : '';
    let dateInfo = normalizeDateDashToDot(rawDateCol);
    if (!dateInfo.dateOnly) {
      dateInfo = normalizeDateDashToDot(cleanText(tr));
    }

    const author = cleanSenderName(cleanText(tds[0])) || 'Преподаватель';
    const subject = cleanText(tds[1]) || '';
    const comment = tds[4] ? cleanText(tds[4]) : (tds[3] ? cleanText(tds[3]) : '');

    links.forEach((a, idx) => {
      const url = resolveUrl(a.getAttribute('href') || '');
      const name = cleanText(a) || decodeURIComponent(url.split('/').pop() || 'Файл');
      const extMatch = name.match(/\.([a-z0-9]{1,5})$/i);
      const ext = extMatch ? extMatch[1].toUpperCase() : 'FILE';

      // Уникальный id для каждого файла даже при нескольких <a> внутри одной <tr>
      const uniqueId = `file_${rowId}_${idx}_${shortHash(url || name)}`;

      files.push({
        id: uniqueId,
        name,
        ext,
        subject,
        size: '',
        author,
        date: dateInfo.dateOnly || dateInfo.full || '',
        comment: comment === name ? '' : comment,
        url
      });
    });
  });

  return files;
}