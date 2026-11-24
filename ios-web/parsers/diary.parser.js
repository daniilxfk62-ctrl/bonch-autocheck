const DOW_SHORT = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
const DOW_FULL = ['Воскресенье', 'Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота'];

function cleanText(el) {
  return (el ? el.textContent || '' : '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
}

function parseIsoDow(iso) {
  if (!iso || !/^\d{4}-\d{2}-\d{2}/.test(iso)) return ['', ''];
  const p = iso.slice(0, 10).split('-');
  const d = new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
  if (Number.isNaN(d.getTime())) return ['', ''];
  return [DOW_SHORT[d.getDay()], DOW_FULL[d.getDay()]];
}

function formatIsoShort(iso) {
  if (!iso || !/^\d{4}-\d{2}-\d{2}/.test(iso)) return '';
  const p = iso.slice(0, 10).split('-');
  return `${p[2]}.${p[1]}`;
}

function extractIsoFromText(text) {
  const s = String(text || '');
  const iso = s.match(/(\d{4}-\d{2}-\d{2})/);
  if (iso) return iso[1];
  const dmy = s.match(/(\d{2})\.(\d{2})\.(\d{4})/);
  if (dmy) return `${dmy[3]}-${dmy[2]}-${dmy[1]}`;
  return '';
}

function detectControlType(rowLabel) {
  if (rowLabel.includes('экзамен')) return 'Экзамен';
  if (rowLabel.includes('зачет с оценк') || rowLabel.includes('зачёт с оценк')) return 'Зачёт с оценкой';
  if (rowLabel.includes('зачет') || rowLabel.includes('зачёт')) return 'Зачёт';
  if (rowLabel.includes('аттестац')) return 'Аттестация';
  return '';
}

function extractAttestationNum(rowLabel) {
  const m = rowLabel.match(/([1-9])/);
  return m ? m[1] : '';
}

function isControlCellApplicable(td, isAttestation) {
  const style = (td.getAttribute('style') || '').toLowerCase();
  if (
    td.hasAttribute('disabled') ||
    style.includes('#ccc') ||
    style.includes('#ddd') ||
    style.includes('gray') ||
    style.includes('silver')
  ) {
    return false;
  }
  if (td.hasAttribute('title') || td.hasAttribute('onclick') || td.hasAttribute('rasp') || td.hasAttribute('id')) {
    return true;
  }
  return isAttestation;
}

function getOrCreateSubject(map, name) {
  if (!map.has(name)) {
    map.set(name, {
      subject: name,
      attended: 0,
      total: 0,
      excusedCount: 0,
      credit: '',
      history: [],
      sessions: [],
      grades: [],
      gradeEntries: [],
      controls: []
    });
  }
  return map.get(name);
}

export function parseDiaryHtml(html) {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const table = doc.querySelector('table.smalltab.simple-little-table')
    || doc.querySelector('table.simple-little-table')
    || Array.from(doc.querySelectorAll('table')).find((t) => t.querySelector('tr[date1]'));

  if (!table) return [];

  const rows = Array.from(table.querySelectorAll('tr'));
  if (!rows.length) return [];

  // Находим строку заголовков предметов
  const headerRow = rows.find((tr) => tr.querySelectorAll('th').length >= 3) || rows[0];
  const columns = Array.from(headerRow.querySelectorAll('th, td')).map((c) => cleanText(c));
  const bySubject = new Map();

  rows.forEach((tr) => {
    if (tr === headerRow) return;
    const tds = Array.from(tr.querySelectorAll('td'));
    if (tds.length < 3) return;

    const date1 = (tr.getAttribute('date1') || '').trim();
    const maxCols = Math.min(tds.length, columns.length);

    // 1. Обычные строки занятий с атрибутом date1="YYYY-MM-DD"
    if (date1) {
      const shortDate = formatIsoShort(date1);
      const [dow, dowFull] = parseIsoDow(date1);

      for (let col = 2; col < maxCols; col++) {
        const subj = columns[col];
        if (!subj) continue;

        const td = tds[col];
        const hasRasp = td.hasAttribute('rasp') && td.getAttribute('rasp') !== '';
        const cellVal = cleanText(td);
        if (!hasRasp && !cellVal) continue;

        const st = getOrCreateSubject(bySubject, subj);
        const upper = cellVal.toUpperCase();

        let status = 'present';
        let absenceVal = '';
        if (/(^|\s)Н($|\s|\/|,)/.test(upper)) {
          status = 'absent';
          absenceVal = 'Н';
        } else if (/(^|\s)Б($|\s|\/|,)/.test(upper)) {
          status = 'sick';
          absenceVal = 'Б';
          st.excusedCount += 1;
        } else if (/(^|\s)У($|\s|\/|,)/.test(upper)) {
          status = 'excused';
          absenceVal = 'У';
          st.excusedCount += 1;
        }

        st.total += 1;
        if (status === 'present') st.attended += 1;
        st.history.push(status === 'present');
        st.sessions.push({
          date: shortDate,
          dateIso: date1,
          dow,
          dowFull,
          status,
          value: absenceVal
        });

        // Извлекаем числовые оценки (2..5) за занятие
        const gradeMatches = cellVal.match(/\b[2-5]\b/g);
        if (gradeMatches) {
          gradeMatches.forEach((g) => {
            st.grades.push(g);
            st.gradeEntries.push({
              value: g,
              display: g,
              date: shortDate,
              dateIso: date1,
              dow,
              dowFull,
              kind: 'lesson',
              label: 'Занятие'
            });
          });
        }
      }
      return;
    }

    // 2. Нижние строки контроля: Аттестация, Зачёт, Зачёт с оценкой, Экзамен
    const rowLabel = `${cleanText(tds[0])} ${cleanText(tds[1])}`.toLowerCase();
    const controlType = detectControlType(rowLabel);
    if (!controlType) return;

    const isAttestation = controlType === 'Аттестация';
    const attNum = isAttestation ? extractAttestationNum(rowLabel) : '';
    const controlLabel = isAttestation
      ? (attNum ? `Аттестация ${attNum}` : 'Аттестация')
      : controlType;

    for (let col = 2; col < maxCols; col++) {
      const subj = columns[col];
      if (!subj) continue;

      const td = tds[col];
      const cellVal = cleanText(td);
      const hasValue = cellVal.length > 0;
      if (!hasValue && !isControlCellApplicable(td, isAttestation)) continue;

      const st = getOrCreateSubject(bySubject, subj);
      if (!isAttestation) st.credit = controlType;

      const rawTitle = (td.getAttribute('title') || '').trim();
      const ctrlDateIso = extractIsoFromText(rawTitle);
      const ctrlShortDate = ctrlDateIso ? formatIsoShort(ctrlDateIso) : rawTitle;
      const [dow, dowFull] = parseIsoDow(ctrlDateIso);

      let normVal = '';
      let passedState = '';

      if (hasValue) {
        const lowVal = cellVal.toLowerCase();
        if (isAttestation) {
          if (lowVal.includes('не аттест') || lowVal.includes('неаттест') || lowVal.includes('не зач') || lowVal.includes('незач') || lowVal === '2') {
            normVal = 'Неаттестован';
            passedState = 'false';
          } else if (lowVal.includes('аттест') || lowVal.includes('зач')) {
            normVal = 'Аттестован';
            passedState = 'true';
          } else {
            normVal = cellVal;
          }
        } else if (controlType === 'Зачёт') {
          if (lowVal.includes('не зач') || lowVal.includes('незач')) {
            normVal = 'Незачёт';
            passedState = 'false';
          } else if (lowVal.includes('зач')) {
            normVal = 'Зачёт';
            passedState = 'true';
          } else {
            normVal = cellVal;
          }
        } else {
          const gm = cellVal.match(/\b([2-5])\b/);
          if (gm) {
            normVal = gm[1];
            passedState = normVal === '2' ? 'false' : 'true';
          } else if (lowVal.includes('незач') || lowVal.includes('не зач') || lowVal.includes('неяв')) {
            normVal = cellVal;
            passedState = 'false';
          } else {
            normVal = cellVal;
          }
        }
      }

      st.controls.push({
        type: controlType,
        label: controlLabel,
        value: normVal,
        rawValue: cellVal,
        passed: passedState,
        date: ctrlShortDate,
        dateIso: ctrlDateIso,
        dow,
        dowFull
      });
    }
  });

  return Array.from(bySubject.values());
}