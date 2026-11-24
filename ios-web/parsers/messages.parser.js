const LK_BASE = 'https://lk.sut.ru';

function cleanText(el) {
  return (el ? el.textContent || '' : '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
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

function extractTimeFromDate(dateStr) {
  const m = String(dateStr || '').match(/(\d{1,2}:\d{2})/);
  return m ? m[1] : String(dateStr || '').slice(0, 5);
}

function extractAttachments(rootEl) {
  if (!rootEl) return [];
  const links = Array.from(rootEl.querySelectorAll('a[href]'));
  const result = [];
  links.forEach((a) => {
    const href = a.getAttribute('href') || '';
    if (!href.includes('subconto/sendto') && !a.hasAttribute('download')) return;
    const url = resolveUrl(href);
    const name = cleanText(a) || decodeURIComponent(url.split('/').pop() || 'Файл');
    result.push({ name, url });
  });
  return result;
}

function parseMessageBodyHtml(bodyHtml) {
  if (!bodyHtml) return { text: '', attachments: [] };
  const doc = new DOMParser().parseFromString(bodyHtml, 'text/html');
  const attachments = extractAttachments(doc.body);
  // Убираем ссылки вложений из основного текста, чтобы не дублировать их
  doc.querySelectorAll('a[href*="subconto/sendto"]').forEach((a) => a.remove());
  const text = (doc.body ? doc.body.innerText || doc.body.textContent || '' : '')
    .replace(/\u00a0/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text, attachments };
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

    const dateStr = cleanText(tds[0]);
    const subject = cleanText(tds[1]);
    const sender = cleanText(tds[2]) || 'Личный кабинет';
    const caption = tds[3] ? cleanText(tds[3]) : '';
    const isUnreadRow = Boolean(tr.querySelector('b, strong')) || tr.classList.contains('unread');

    const bodyInfo = parseMessageBodyHtml(messageBodies[msgId] || '');
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
        time: extractTimeFromDate(dateStr),
        unread: isUnreadRow,
        muted: prev ? !!prev.muted : false,
        thread: []
      });
    }

    const group = threadsBySender.get(sender);
    if (isUnreadRow) group.unread = true;
    group.thread.push({
      from: 'them',
      subject,
      text: bodyText,
      caption,
      date: dateStr,
      time: extractTimeFromDate(dateStr),
      attachments
    });
  });

  // Сообщения в ЛК идут сверху вниз (новые сверху), для чата разворачиваем хронологически
  const result = Array.from(threadsBySender.values());
  result.forEach((g) => {
    g.thread.reverse();
    const last = g.thread[g.thread.length - 1];
    if (last) g.time = last.time || g.time;
  });
  return result;
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

    const date = cleanText(tds[0]);
    const subject = cleanText(tds[1]);
    const author = cleanText(tds[2]) || 'Преподаватель';
    const comment = tds[4] ? cleanText(tds[4]) : (tds[3] ? cleanText(tds[3]) : '');

    links.forEach((a, idx) => {
      const url = resolveUrl(a.getAttribute('href') || '');
      const name = cleanText(a) || decodeURIComponent(url.split('/').pop() || 'Файл');
      const extMatch = name.match(/\.([a-z0-9]{1,5})$/i);
      const ext = extMatch ? extMatch[1].toUpperCase() : 'FILE';

      files.push({
        id: `file_${rowId}_${idx}`,
        name,
        ext,
        subject,
        size: '',
        author,
        date,
        comment: comment === name ? '' : comment,
        url
      });
    });
  });

  return files;
}