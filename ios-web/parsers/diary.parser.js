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
  const dmy = s.match(/(\d{2})[.-](\d{2})[.-](\d{4})/);
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

/**
 * Проверка серой/неактивной ячейки контроля в jurnal_dnevnik.php:
 * серые ячейки имеют background-color:#ebebeb или onclick='spanOutputclose()'.
 * Активные (белые) ячейки имеют title='' (или title с датой) и не имеют серого фона.
 */
function isGrayInactiveControlCell(td) {
  const styleNoSpace = (td.getAttribute('style') || '').toLowerCase().replace(/\s+/g, '');
  const onclickLow = (td.getAttribute('onclick') || '').toLowerCase();
  if (
    td.hasAttribute('disabled') ||
    styleNoSpace.includes('#ebebeb') ||
    styleNoSpace.includes('rgb(235,235,235)') ||
    styleNoSpace.includes('#ccc') ||
    styleNoSpace.includes('#ddd') ||
    styleNoSpace.includes('background:gray') ||
    styleNoSpace.includes('background-color:gray') ||
    styleNoSpace.includes('background:silver') ||
    onclickLow.includes('spanoutputclose')
  ) {
    return true;
  }
  return false;
}

function isControlCellApplicable(td, isAttestation) {
  if (isGrayInactiveControlCell(td)) return false;
  // Белые ячейки контроля в lk.sut.ru имеют атрибут title (включая пустой title='')
  if (td.hasAttribute('title')) return true;
  if (td.hasAttribute('onclick') || td.hasAttribute('rasp') || td.hasAttribute('id')) return true;
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

  // Разворачиваем заголовок с учётом возможного colspan
  const headerRow = rows.find((tr) => tr.querySelectorAll('th').length >= 3) || rows[0];
  const columns = [];
  Array.from(headerRow.querySelectorAll('th, td')).forEach((cell) => {
    const label = cleanText(cell);
    const span = Math.max(1, parseInt(cell.getAttribute('colspan') || '1', 10));
    for (let s = 0; s < span; s++) columns.push(label);
  });

  const bySubject = new Map();

  rows.forEach((tr) => {
    if (tr === headerRow) return;
    const tds = Array.from(tr.querySelectorAll('td'));
    if (tds.length < 2) return;

    const date1 = (tr.getAttribute('date1') || '').trim();

    // 1. Строки занятий (date1="YYYY-MM-DD")
    if (date1) {
      const shortDate = formatIsoShort(date1);
      const [dow, dowFull] = parseIsoDow(date1);

      let colIdx = 0;
      for (let i = 0; i < tds.length; i++) {
        const td = tds[i];
        const span = Math.max(1, parseInt(td.getAttribute('colspan') || '1', 10));
        if (colIdx >= 2 && colIdx < columns.length) {
          const subj = columns[colIdx];
          if (subj) {
            const hasRasp = td.hasAttribute('rasp') && td.getAttribute('rasp') !== '';
            const cellVal = cleanText(td);
            if (hasRasp || cellVal) {
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
          }
        }
        colIdx += span;
      }
      return;
    }

    // 2. Нижние 4 строки контроля (с учётом <td colspan="2"> в первой ячейке!)
    const firstSpan = Math.max(1, parseInt(tds[0].getAttribute('colspan') || '1', 10));
    const rowLabel = firstSpan >= 2
      ? cleanText(tds[0]).toLowerCase()
      : `${cleanText(tds[0])} ${cleanText(tds[1])}`.toLowerCase();
    const controlType = detectControlType(rowLabel);
    if (!controlType) return;

    const isAttestation = controlType === 'Аттестация';
    const attNum = isAttestation ? extractAttestationNum(rowLabel) : '';
    const controlLabel = isAttestation
      ? (attNum ? `Аттестация ${attNum}` : 'Аттестация')
      : controlType;

    // Точное сопоставление колонок с учётом colspan
    let colIdx = 0;
    for (let i = 0; i < tds.length; i++) {
      const td = tds[i];
      const span = Math.max(1, parseInt(td.getAttribute('colspan') || '1', 10));

      if (colIdx >= 2 && colIdx < columns.length) {
        const subj = columns[colIdx];
        if (subj && !isGrayInactiveControlCell(td)) {
          const cellVal = cleanText(td);
          const hasValue = cellVal.length > 0;
          if (hasValue || isControlCellApplicable(td, isAttestation)) {
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
        }
      }
      colIdx += span;
    }
  });

  return Array.from(bySubject.values());
}