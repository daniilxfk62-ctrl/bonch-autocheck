#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BonchPhone Direct — Автоотметка СПбГУТ (lk.sut.ru) НАПРЯМУЮ С ТЕЛЕФОНА БЕЗ ОБЛАКА И БЕЗ ПК
==========================================================================================
Работает на 100% локально на самом телефоне через мобильный интернет или Wi-Fi телефона:
- Никаких внешних серверов и облаков: телефон сам шлет запросы напрямую на https://lk.sut.ru/cabinet/
- Как запустить на Android за 2 минуты (через бесплатное приложение Pydroid 3 из Google Play или Termux):
  1. Установите Pydroid 3 на телефон.
  2. В разделе Pip установите: `requests` и `beautifulsoup4`
  3. Откройте этот файл `sut_vpn_app.py` и нажмите желтую кнопку Play.
  4. На экране телефона сразу откроется мобильный интерфейс в стиле VPN (`http://127.0.0.1:8080`):
     вводите свой логин и пароль от lk.sut.ru, нажимаете большую круглую кнопку «ВКЛЮЧИТЬ» —
     и телефон сам каждые 5 минут в течение 30 минут от начала пары жмет «Обновить» и «Отметиться»!
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import logging
import re
import threading
import webbrowser
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import requests
from bs4 import BeautifulSoup

# =====================================================================
# ПРЯМЫЕ АДРЕСА ЛК СПбГУТ (lk.sut.ru)
# =====================================================================
BASE_URL = "https://lk.sut.ru/cabinet"
LOGIN_PAGE_URL = f"{BASE_URL}/?login=yes"
AUTH_URL = f"{BASE_URL}/lib/autentificationok.php"
INI_URL = f"{BASE_URL}/ini.php"
SCHEDULE_URL = f"{BASE_URL}/project/cabinet/forms/raspisanie.php"

CONFIG_FILE = Path.home() / ".bonch_phone_direct.json"

BELL_SCHEDULE = [
    "09:00",  # 1 пара
    "10:45",  # 2 пара
    "13:00",  # 3 пара
    "14:45",  # 4 пара
    "16:30",  # 5 пара
    "18:15",  # 6 пара
    "20:00",  # 7 пара
]

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("BonchPhoneDirect")


# =====================================================================
# 1. ПРЯМОЙ HTTP-КЛИЕНТ (С ИНТЕРНЕТА ТЕЛЕФОНА ПРЯМО НА LK.SUT.RU)
# =====================================================================
@dataclass
class LessonItem:
    num: str
    time_range: str
    start_time: str
    subject: str
    rasp_id: Optional[str] = None
    week_id: Optional[str] = None
    already_checked: bool = False


class SutDirectClient:
    """Отправляет запросы с телефона напрямую на lk.sut.ru без браузера и без облака."""

    def __init__(self, login: str, password: str) -> None:
        self.login = login.strip()
        self.password = password.strip()
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 14; Mobile) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                ),
                "Referer": LOGIN_PAGE_URL,
                "Origin": "https://lk.sut.ru",
            }
        )
        self.is_authenticated = False

    def authenticate(self) -> Tuple[bool, str]:
        try:
            self.session.get(LOGIN_PAGE_URL, timeout=15)
            resp = self.session.post(
                AUTH_URL,
                params={"users": self.login, "parole": self.password},
                data={"users": self.login, "parole": self.password},
                timeout=15,
            )
            text = resp.text.strip()
            if text == "1" or "1" in text:
                self.session.get(INI_URL, timeout=15)
                self.is_authenticated = True
                return True, f"Телефон подключен к ЛК СПбГУТ ({self.login})"

            self.is_authenticated = False
            return False, "Неверный логин или пароль от lk.sut.ru"
        except Exception as exc:
            self.is_authenticated = False
            return False, f"Нет ответа от lk.sut.ru (проверьте интернет на телефоне): {exc}"

    def fetch_schedule_and_checkin(self, marked_ids: Set[str]) -> Tuple[List[LessonItem], List[str]]:
        if not self.is_authenticated:
            ok, _ = self.authenticate()
            if not ok:
                return [], []

        newly_checked: List[str] = []
        lessons: List[LessonItem] = []

        try:
            resp = self.session.post(SCHEDULE_URL, timeout=15)
            if "autentification" in resp.text.lower() or 'id="users"' in resp.text:
                ok, _ = self.authenticate()
                if not ok:
                    return [], []
                resp = self.session.post(SCHEDULE_URL, timeout=15)

            soup = BeautifulSoup(resp.text, "html.parser")
            open_zan_pattern = re.compile(r"open_zan\s*\(\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*\)")

            for tr in soup.find_all("tr"):
                row_text = tr.get_text(" ", strip=True)
                time_match = re.search(
                    r"([01]?\d|2[0-3]):([0-5]\d)\s*[-–—]\s*(?:[01]?\d|2[0-3]):[0-5]\d", row_text
                )
                start_time = f"{int(time_match.group(1)):02d}:{time_match.group(2)}" if time_match else ""

                row_html = str(tr)
                zan_match = open_zan_pattern.search(row_html)
                rasp_id = zan_match.group(1) if zan_match else None
                week_id = zan_match.group(2) if zan_match else None

                already = any(
                    phrase in row_text.lower()
                    for phrase in ("вы присутствуете", "отмечен", "присутствует")
                )

                if time_match or rasp_id or already:
                    lessons.append(
                        LessonItem(
                            num=str(len(lessons) + 1),
                            time_range=time_match.group(0) if time_match else "Пара",
                            start_time=start_time or "09:00",
                            subject=row_text[:90],
                            rasp_id=rasp_id,
                            week_id=week_id,
                            already_checked=already,
                        )
                    )

                if rasp_id and week_id and rasp_id not in marked_ids:
                    if self.send_open_zan(rasp_id, week_id):
                        marked_ids.add(rasp_id)
                        newly_checked.append(row_text[:70] if row_text else f"Занятие #{rasp_id}")

            for match in open_zan_pattern.finditer(resp.text):
                r_id, w_id = match.group(1), match.group(2)
                if r_id not in marked_ids:
                    if self.send_open_zan(r_id, w_id):
                        marked_ids.add(r_id)
                        newly_checked.append(f"Занятие #{r_id} (неделя {w_id})")

            return lessons, newly_checked
        except Exception as exc:
            logger.warning("Ошибка опроса расписания с телефона: %s", exc)
            return lessons, newly_checked

    def send_open_zan(self, rasp_id: str, week_id: str) -> bool:
        try:
            payload = {"open": 1, "rasp": rasp_id, "week": week_id}
            r = self.session.post(SCHEDULE_URL, data=payload, params=payload, timeout=15)
            return r.status_code == 200
        except Exception:
            return False


# =====================================================================
# 2. ФОНОВЫЙ ПРОЦЕСС НА ТЕЛЕФОНЕ (КАЖДЫЕ 5 МИН В ТЕЧЕНИЕ 30 МИН ОТ НАЧАЛА ПАРЫ)
# =====================================================================
@dataclass
class PhoneCheckInWorker:
    login: str
    password: str
    interval_minutes: float = 5.0
    window_minutes: int = 30
    active: bool = False
    status_text: str = "Отключено"
    marked_today: Set[str] = field(default_factory=set)
    parsed_start_times: Set[str] = field(default_factory=set)
    logs: List[str] = field(default_factory=list)
    _thread: Optional[threading.Thread] = field(default=None, repr=False)
    _stop_event: threading.Event = field(default_factory=threading.Event, repr=False)

    def add_log(self, text: str) -> None:
        stamp = dt.datetime.now().strftime("%H:%M:%S")
        line = f"[{stamp}] {text}"
        self.logs.append(line)
        if len(self.logs) > 80:
            self.logs = self.logs[-80:]
        logger.info("%s", text)

    def is_in_30min_lesson_window(self) -> Optional[Tuple[str, float]]:
        now = dt.datetime.now()
        all_times = sorted(set(BELL_SCHEDULE) | self.parsed_start_times)
        for t_str in all_times:
            hh, mm = map(int, t_str.split(":"))
            start_dt = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
            diff_min = (now - start_dt).total_seconds() / 60.0
            if -1.0 <= diff_min <= self.window_minutes:
                return t_str, diff_min
        return None

    def start(self) -> Tuple[bool, str]:
        if self.active:
            return True, "Уже работает на телефоне"

        client = SutDirectClient(self.login, self.password)
        ok, msg = client.authenticate()
        self.add_log(msg)
        if not ok:
            self.status_text = "Ошибка входа"
            return False, msg

        self.active = True
        self.status_text = "Работает напрямую с телефона"
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, args=(client,), daemon=True)
        self._thread.start()
        return True, msg

    def stop(self) -> None:
        self.active = False
        self.status_text = "Отключено"
        self._stop_event.set()
        self.add_log("Автоотметка остановлена.")

    def _run_loop(self, client: SutDirectClient) -> None:
        current_day = dt.date.today()
        self._poll_once(client)

        while not self._stop_event.is_set():
            if dt.date.today() != current_day:
                current_day = dt.date.today()
                self.marked_today.clear()

            win = self.is_in_30min_lesson_window()
            if win is not None:
                start_str, elapsed = win
                self.status_text = f"Пара {start_str}: опрос каждые {self.interval_minutes:g} мин"
                self.add_log(
                    f"Окно пары {start_str} ({max(0.0, elapsed):.0f}/{self.window_minutes} мин) — прямой запрос с телефона..."
                )
                self._poll_once(client)
                self._stop_event.wait(timeout=max(15, int(self.interval_minutes * 60)))
            else:
                self.status_text = "Активна (ждем начала следующей пары)"
                self._stop_event.wait(timeout=30)

    def _poll_once(self, client: SutDirectClient) -> None:
        lessons, checked = client.fetch_schedule_and_checkin(self.marked_today)
        for les in lessons:
            if les.start_time:
                self.parsed_start_times.add(les.start_time)
        if checked:
            for title in checked:
                self.add_log(f"✅ ОТМЕЧЕН НА ПАРЕ: {title}")
        else:
            self.add_log("Проверено с телефона: кнопка «Отметиться» еще не появилась.")


# =====================================================================
# 3. ЛОКАЛЬНЫЙ МОБИЛЬНЫЙ ИНТЕРФЕЙС НА ТЕЛЕФОНЕ (127.0.0.1 — БЕЗ ОБЛАКА!)
# =====================================================================
CURRENT_WORKER: Optional[PhoneCheckInWorker] = None

MOBILE_VPN_HTML = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>BonchPhone VPN</title>
<style>
  body { margin:0; background:#090d16; color:#f8fafc; font-family:system-ui,-apple-system,sans-serif; display:flex; justify-content:center; padding:18px; }
  .card { width:100%; max-width:400px; background:#111827; border:1px solid #1e293b; border-radius:24px; padding:22px; box-sizing:border-box; box-shadow:0 16px 40px rgba(0,0,0,0.6); }
  input, select { width:100%; box-sizing:border-box; padding:12px 14px; margin-top:6px; margin-bottom:12px; border-radius:12px; border:1px solid #334155; background:#1e293b; color:#fff; font-size:14px; }
  .power-wrap { display:flex; flex-direction:column; align-items:center; margin:18px 0; }
  .power-btn { width:140px; height:140px; border-radius:999px; border:5px solid #475569; background:#1e293b; color:#fff; font-weight:800; font-size:14px; cursor:pointer; transition:all 0.25s; }
  .power-btn.on { background:linear-gradient(180deg,#10b981,#059669); border-color:#6ee7b7; color:#052e16; box-shadow:
