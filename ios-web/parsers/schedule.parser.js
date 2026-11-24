const SHORT_DOWS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
const PAIR_SLOTS = {
  1: ['09:00', '10:35'],
  2: ['10:45', '12:20'],
  3: ['13:00', '14:35'],
  4: ['14:45', '16:20'],
  5: ['16:30', '18:05'],
  6: ['18:15', '19:50'],
  7: ['20:00', '21:35']
};

function cleanText(el) {
  return (el ? el.textContent || '' : '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
}

function normalizeLessonType(raw) {
  const low = String(raw || '').toLowerCase();
  if (low.includes('лекц')) return 'Лекция';
  if (low.includes('лаб')) return 'Лаба';
  if (low.includes('практ') || low.includes('семин')) return 'Практика';
  return 'Практика';
}

function extractTimeRange(text) {
  const m = String(text || '').match(/(\d{1,2}[:.]\d{2})\s*[-–—]\s*(\d{1,2}[:.]\d{2})/);
  if (m) {
    return [m[1].replace('.', ':').padStart(5, '0'), m[2].replace('.', ':').padStart(5, '0')];
  }
  const pairMatch = String(text || '').match(/\b([1-7])\b/);
  if (pairMatch && PAIR_SLOTS[pairMatch[1]]) {
    return PAIR_SLOTS[pairMatch[1]];
  }
  return ['09:00', '10:35'];
}

function parseDateToDowIdx(dateStr) {
  const p = String(dateStr || '').split('.');
  if (p.length < 3) return 0;
  const d = new Date(parseInt(p[2], 10), parseInt(p[1], 10) - 1, parseInt(p[0], 10));
  if (Number.isNaN(d.getTime())) return 0;
  return Math.max(0, Math.min(5, (d.getDay() + 6) % 7));
}

function formatDate(d) {
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
}

function parseSingleWeekHtml(html, forcedWeekNum, checkinMap) {
  if (!html) return [];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const fullText = doc.body ? doc.body.textContent || '' : '';

  let weekNum = Number(forcedWeekNum) || 0;
  if (!weekNum) {
    const wm = fullText.match(/Недел[яи]\s*№?\s*(\d{1,2})/i);
    weekNum = wm ? parseInt(wm[1], 10) : 1;
  }

  const daysByDate = new Map();
  let currentDateStr = '';

  const rows = Array.from(doc.querySelectorAll('tr'));
  rows.forEach((tr) => {
    const rowText = cleanText(tr);
    const dateMatch = rowText.match(/(\d{2}\.\d{2}\.\d{4})/);
    const cells = Array.from(tr.querySelectorAll('td, th'));

    // Строка-заголовок дня недели с датой
    if (dateMatch && cells.length <= 2) {
      currentDateStr = dateMatch[1];
      if (!daysByDate.has(currentDateStr)) {
        const dowIdx = parseDateToDowIdx(currentDateStr);
        daysByDate.set(currentDateStr, {
          weekNum,
          dateStr: currentDateStr,
          dowIdx,
          shortDow: SHORT_DOWS[dowIdx] || 'Пн',
          lessons: []
        });
      }
      return;
    }

    if (cells.length < 3) return;

    // Иногда дата указана прямо в первой ячейке строки
    if (dateMatch && !currentDateStr) {
      currentDateStr = dateMatch[1];
    }
    if (!currentDateStr) return;

    if (!daysByDate.has(currentDateStr)) {
      const dowIdx = parseDateToDowIdx(currentDateStr);
      daysByDate.set(currentDateStr, {
        weekNum,
        dateStr: currentDateStr,
        dowIdx,
        shortDow: SHORT_DOWS[dowIdx] || 'Пн',
        lessons: []
      });
    }

    const timeCellText = cleanText(cells[0]);
    if (!/\d{1,2}[:.]\d{2}/.test(timeCellText) && !/^[1-7]\b/.test(timeCellText)) {
      return;
    }

    const [startTime, endTime] = extractTimeRange(timeCellText);

    // Ячейка предмета и типа занятия
    const subjCell = cells[1];
    const boldEl = subjCell.querySelector('b, strong');
    const smallEl = subjCell.querySelector('small, i, span');
    const subjRaw = cleanText(subjCell);

    let subjectTitle = boldEl ? cleanText(boldEl) : subjRaw;
    let typeRaw = smallEl ? cleanText(smallEl) : '';

    if (!typeRaw && cells.length >= 5) {
      const c2 = cleanText(cells[2]);
      if (/лекц|практ|лаб|семин/i.test(c2)) {
        typeRaw = c2;
      }
    }
    if (!typeRaw) {
      const paren = subjRaw.match(/\(([^)]*(?:лекц|практ|лаб|семин)[^)]*)\)/i);
      if (paren) {
        typeRaw = paren[1];
        subjectTitle = subjectTitle.replace(paren[0], '').trim();
      }
    }

    const lessonType = normalizeLessonType(typeRaw || subjRaw);

    // Аудитория в lk.sut.ru обычно в 4-й <td> ("404; Б22/1"), корпус матчится по "; <корпус>/<номер>"
    let room = '—';
    let teacher = 'Преподаватель не указан';
    const roomCandidate = cells[3] ? cleanText(cells[3]) : '';
    const thirdCandidate = cells[2] ? cleanText(cells[2]) : '';

    if (/;\s*[^\s;]+\/\d+/.test(roomCandidate) || /\d{2,4}/.test(roomCandidate)) {
      room = roomCandidate;
      if (thirdCandidate && !/лекц|практ|лаб|семин/i.test(thirdCandidate)) {
        teacher = thirdCandidate;
      } else if (cells[4]) {
        teacher = cleanText(cells[4]) || teacher;
      }
    } else if (/;\s*[^\s;]+\/\d+/.test(thirdCandidate)) {
      room = thirdCandidate;
      if (roomCandidate) teacher = roomCandidate;
    } else {
      if (roomCandidate) room = roomCandidate;
      if (thirdCandidate && !/лекц|практ|лаб|семин/i.test(thirdCandidate)) teacher = thirdCandidate;
    }

    // Извлекаем open_zan(raspId, weekId) и статус отметки
    const rowHtml = tr.innerHTML || '';
    const openZan = rowHtml.match(/open_zan\(\s*(\d+)\s*,\s*(\d+)\s*\)/);
    const raspId = openZan ? openZan[1] : '';
    const weekId = openZan ? openZan[2] : String(weekNum);

    const lowRow = rowText.toLowerCase();
    let checked = lowRow.includes('вы отмечены') || lowRow.includes('отмечен') || lowRow.includes('присутствовал');
    const waitingTeacher = !checked && !raspId && (lowRow.includes('ожидан') || lowRow.includes('не начато'));
    let checkTime = '';
    let checkedBy = '';

    // Совмещаем с локальной историей фоновых отметок из Pulse/state.json
    const ckKey = `${currentDateStr}|${startTime}`;
    if (checkinMap.has(ckKey)) {
      const ck = checkinMap.get(ckKey);
      checked = true;
      checkTime = ck.time || '';
      checkedBy = ck.by || 'auto';
    }

    daysByDate.get(currentDateStr).lessons.push({
      startTime,
      endTime,
      subjectTitle: subjectTitle || 'Предмет',
      lessonType,
      room,
      teacher,
      raspId,
      weekId,
      waitingTeacher,
      checked,
      checkTime,
      checkedBy
    });
  });

  // Достраиваем полную 6-дневную учебную неделю (Пн–Сб), чтобы в вертикальной ленте не было дыр
  const parsedList = Array.from(daysByDate.values());
  if (!parsedList.length) return [];

  const first = parsedList[0];
  const fp = first.dateStr.split('.');
  const firstDate = new Date(parseInt(fp[2], 10), parseInt(fp[1], 10) - 1, parseInt(fp[0], 10));
  const monday = new Date(firstDate);
  monday.setDate(firstDate.getDate() - first.dowIdx);

  const fullWeek = [];
  for (let i = 0; i < 6; i++) {
    const dt = new Date(monday);
    dt.setDate(monday.getDate() + i);
    const ds = formatDate(dt);
    if (daysByDate.has(ds)) {
      fullWeek.push(daysByDate.get(ds));
    } else {
      fullWeek.push({
        weekNum,
        dateStr: ds,
        dowIdx: i,
        shortDow: SHORT_DOWS[i],
        lessons: []
      });
    }
  }
  return fullWeek;
}

export function parseScheduleHtml(singleHtml, scheduleWeeks = [], checkins = []) {
  const checkinMap = new Map();
  (Array.isArray(checkins) ? checkins : []).forEach((c) => {
    if (c && c.dateStr && c.startTime) {
      checkinMap.set(`${c.dateStr}|${c.startTime}`, c);
    }
  });

  const allDaysByDate = new Map();
  const sources = [];
  if (Array.isArray(scheduleWeeks) && scheduleWeeks.length) {
    scheduleWeeks.forEach((w) => {
      if (w && w.html) sources.push({ weekNum: w.weekNum, html: w.html });
    });
  }
  if (singleHtml) {
    sources.push({ weekNum: 0, html: singleHtml });
  }

  sources.forEach((src) => {
    const days = parseSingleWeekHtml(src.html, src.weekNum, checkinMap);
    days.forEach((d) => {
      allDaysByDate.set(d.dateStr, d);
    });
  });

  return Array.from(allDaysByDate.values()).sort((a, b) => {
    const pa = a.dateStr.split('.').reverse().join('');
    const pb = b.dateStr.split('.').reverse().join('');
    return pa.localeCompare(pb);
  });
}

export function buildSmartCatalog(days) {
  const map = new Map();
  (Array.isArray(days) ? days : []).forEach((day) => {
    (Array.isArray(day.lessons) ? day.lessons : []).forEach((l) => {
      const name = l.subjectTitle || 'Предмет';
      if (!map.has(name)) {
        map.set(name, { subject: name, lecCount: 0, pracCount: 0, labCount: 0 });
      }
      const item = map.get(name);
      if (l.lessonType === 'Лекция') item.lecCount += 1;
      else if (l.lessonType === 'Лаба') item.labCount += 1;
      else item.pracCount += 1;
    });
  });
  return Array.from(map.values()).sort((a, b) => a.subject.localeCompare(b.subject, 'ru'));
}