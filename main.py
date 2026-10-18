#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Bonch Direct AutoCheck — Автоотметка СПбГУТ (lk.sut.ru) с уведомлениями
Поддерживает:
1. Сборку в нативный Android APK через Flet (flet build apk)
2. Прямой запуск в Pydroid 3 (автоматический fallback на локальный веб-интерфейс с Push-уведомлениями)
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re
import subprocess
import threading
import webbrowser
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import List, Optional, Set, Tuple

import requests
from bs4 import BeautifulSoup

# Проверяем наличие Flet (для APK)
try:
    import flet as ft
    HAS_FLET = True
except ImportError:
    HAS_FLET = False

# Проверяем наличие plyer (для системных уведомлений Android)
try:
    from plyer import notification as plyer_notification
    HAS_PLYER = True
except ImportError:
    HAS_PLYER = False

# =====================================================================
# АДРЕСА ЛК СПбГУТ (lk.sut.ru)
# =====================================================================
BASE_URL = "https://lk.sut.ru/cabinet"
LOGIN_PAGE_URL = f"{BASE_URL}/?login=yes"
AUTH_URL = f"{BASE_URL}/lib/autentificationok.php"
INI_URL = f"{BASE_URL}/ini.php"
SCHEDULE_URL = f"{BASE_URL}/project/cabinet/forms/raspisanie.php"

CONFIG_FILE = Path.home() / ".bonch_autocheck_config.json"

# Расписание звонков СПбГУТ (включая 10:30 для физкультуры)
BELL_SCHEDULE = [
    "09:00",  # 1 пара (09:00 - 10:35)
    "10:30",  # Физкультура (10.30 - 12.00)
    "10:45",  # 2 пара (10:45 - 12:20)
    "13:00",  # 3 пара (13:00 - 14:35)
    "14:45",  # 4 пара (14:45 - 16:20)
    "16:30",  # 5 пара (16:30 - 18:05)
    "18:15",  # 6 пара (18:15 - 19:50)
    "20:00",  # 7 пара
]

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("BonchAutoCheck")


# =====================================================================
# СОХРАНЕНИЕ НАСТРОЕК И ОТПРАВКА УВЕДОМЛЕНИЙ
# =====================================================================
def load_saved_config() -> dict:
    try:
        if CONFIG_FILE.exists():
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {}


def save_config(
    login: str,
    password: str,
    interval: str,
    mode: str,
    tg_token: str = "",
    tg_chat_id: str = "",
) -> None:
    try:
        CONFIG_FILE.write_text(
            json.dumps(
                {
                    "login": login,
                    "password": password,
                    "interval": interval,
                    "mode": mode,
                    "tg_token": tg_token,
                    "tg_chat_id": tg_chat_id,
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
    except Exception:
        pass


def send_phone_notification(
    title: str,
    message: str,
    tg_token: str = "",
    tg_chat_id: str = "",
) -> None:
    """Отправляет уведомление на телефон всеми доступными способами."""
    # 1. Локальное системное уведомление через plyer (если доступно в APK/Pydroid)
    if HAS_PLYER:
        try:
            plyer_notification.notify(
                title=title,
                message=message,
                app_name="Bonch AutoCheck",
                timeout=10,
            )
        except Exception:
            pass

    # 2. Локальное уведомление через Termux API (если запущено в среде Termux)
    try:
        subprocess.run(
            ["termux-notification", "--title", title, "--content", message],
            timeout=3,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass

    # 3. Пуш-уведомление в Telegram (если пользователь указал токен и chat_id)
    if tg_token.strip() and tg_chat_id.strip():
        try:
            requests.post(
                f"https://api.telegram.org/bot{tg_token.strip()}/sendMessage",
                json={
                    "chat_id": tg_chat_id.strip(),
                    "text": f"{title}\n{message}",
                },
                timeout=10,
            )
        except Exception as exc:
            logger.warning("Не удалось отправить пуш в Telegram: %s", exc)


# =====================================================================
# КЛИЕНТ ДЛЯ РАБОТЫ С LK.SUT.RU
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
                "X-Requested-With": "XMLHttpRequest",
            }
        )
        self.is_authenticated = False

    def _decode_response(self, resp: requests.Response) -> str:
        for enc in ("utf-8", "windows-1251", resp.apparent_encoding or "utf-8"):
            try:
                text = resp.content.decode(enc)
                if any(
                    w in text.lower()
                    for w in ("расписание", "занят", "кабинет", "пароль", "присутств", "начать", "1")
                ):
                    return text
            except Exception:
                continue
        return resp.text

    def authenticate(self) -> Tuple[bool, str]:
        if not self.login or not self.password:
            return False, "Введите логин и пароль от lk.sut.ru"
        try:
            self.session.cookies.clear()
            self.session.get(LOGIN_PAGE_URL, timeout=15)
            resp = self.session.post(
                AUTH_URL,
                params={"users": self.login, "parole": self.password},
                data={"users": self.login, "parole": self.password},
                timeout=15,
            )
            text = resp.text.strip()
            if text == "1" or "1" in text[:5]:
                self.session.get(INI_URL, timeout=15)
                self.session.get(LOGIN_PAGE_URL, timeout=15)
                self.is_authenticated = True
                return True, f"Подключено к ЛК СПбГУТ ({self.login})"

            self.is_authenticated = False
            return False, "Неверный логин или пароль от lk.sut.ru"
        except Exception as exc:
            self.is_authenticated = False
            return False, f"Ошибка подключения к lk.sut.ru: {exc}"

    def fetch_schedule_and_checkin(
        self, marked_ids: Set[str]
    ) -> Tuple[List[LessonItem], List[str], str]:
        if not self.is_authenticated:
            ok, msg = self.authenticate()
            if not ok:
                return [], [], msg

        newly_checked: List[str] = []
        lessons: List[LessonItem] = []

        try:
            resp = self.session.post(SCHEDULE_URL, timeout=15)
            html = self._decode_response(resp)

            if (
                len(html.strip()) < 50
                or "autentification" in html.lower()
                or "<table" not in html.lower()
            ):
                ok, msg = self.authenticate()
                if not ok:
                    return [], [], msg
                resp = self.session.post(SCHEDULE_URL, timeout=15)
                html = self._decode_response(resp)

            soup = BeautifulSoup(html, "html.parser")
            open_zan_pattern = re.compile(
                r"open_zan\s*\(\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*\)"
            )

            # Поддержка как двоеточия (13:00-14:35), так и точки (10.30-12.00)
            time_regex = re.compile(
                r"([01]?\d|2[0-3])[:.]([0-5]\d)\s*[-–—]\s*(?:[01]?\d|2[0-3])[:.][0-5]\d"
            )

            for tr in soup.find_all("tr"):
                row_text = tr.get_text(" ", strip=True)
                if not row_text:
                    continue

                time_match = time_regex.search(row_text)
                start_time = (
                    f"{int(time_match.group(1)):02d}:{time_match.group(2)}"
                    if time_match
                    else ""
                )

                row_html = str(tr)
                zan_match = open_zan_pattern.search(row_html)
                rasp_id = zan_match.group(1) if zan_match else None
                week_id = zan_match.group(2) if zan_match else None

                already = any(
                    phrase in row_text.lower()
                    for phrase in ("вы присутствуете", "отмечен", "присутствует")
                )

                if already and rasp_id:
                    marked_ids.add(rasp_id)

                if rasp_id and week_id and not already and rasp_id not in marked_ids:
                    if self.send_open_zan(rasp_id, week_id):
                        marked_ids.add(rasp_id)
                        already = True
                        clean_subject = row_text[:75] if row_text else f"Занятие #{rasp_id}"
                        newly_checked.append(clean_subject)

                if time_match or rasp_id or already:
                    lessons.append(
                        LessonItem(
                            num=str(len(lessons) + 1),
                            time_range=time_match.group(0) if time_match else "Пара",
                            start_time=start_time or "09:00",
                            subject=row_text[:85],
                            rasp_id=rasp_id,
                            week_id=week_id,
                            already_checked=already
                            or (rasp_id in marked_ids if rasp_id else False),
                        )
                    )

            # Дополнительный проход по всему HTML на случай нестандартной верстки кнопки
            for match in open_zan_pattern.finditer(html):
                r_id, w_id = match.group(1), match.group(2)
                if r_id not in marked_ids:
                    if self.send_open_zan(r_id, w_id):
                        marked_ids.add(r_id)
                        newly_checked.append(f"Занятие #{r_id} (неделя {w_id})")

            return lessons, newly_checked, "ОК"
        except Exception as exc:
            return lessons, newly_checked, f"Ошибка опроса: {exc}"

    def send_open_zan(self, rasp_id: str, week_id: str) -> bool:
        try:
            payload = {"open": 1, "rasp": rasp_id, "week": week_id}
            r = self.session.post(SCHEDULE_URL, data=payload, params=payload, timeout=15)
            if r.status_code != 200:
                return False
            verify_resp = self.session.post(SCHEDULE_URL, timeout=15)
            verify_html = self._decode_response(verify_resp).lower()
            return "присутств" in verify_html or f"open_zan({rasp_id}" not in verify_html
        except Exception:
            return False


def check_lesson_window(mode: str, parsed_times: Set[str]) -> Optional[str]:
    """
    Проверяет, попадает ли текущее время в окно пары.
    Кнопка «Начать занятие» появляется за 10 минут до начала пары (-10.0 <= diff_min).
    """
    if mode == "always":
        return "Постоянный режим"
    window_min = 30 if mode == "first_30" else 95
    now = dt.datetime.now()
    all_times = sorted(set(BELL_SCHEDULE) | parsed_times)
    for t_str in all_times:
        try:
            hh, mm = map(int, t_str.replace(".", ":").split(":"))
            start_dt = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
            diff_min = (now - start_dt).total_seconds() / 60.0
            if -10.0 <= diff_min <= window_min:
                if diff_min < 0:
                    return f"Пара в {t_str} (до начала {abs(int(diff_min))} мин)"
                return f"Пара {t_str} (идет {int(diff_min)} мин)"
        except Exception:
            continue
    return None


# =====================================================================
# ВАРИАНТ 1: ГРАФИЧЕСКИЙ ИНТЕРФЕЙС FLET (ДЛЯ APK)
# =====================================================================
def flet_main(page: "ft.Page") -> None:
    page.title = "Bonch AutoCheck"
    page.theme_mode = ft.ThemeMode.DARK
    page.bgcolor = "#090d16"
    page.padding = 16
    page.scroll = ft.ScrollMode.ADAPTIVE

    cfg = load_saved_config()

    login_input = ft.TextField(
        label="Логин / Email от lk.sut.ru",
        value=cfg.get("login", ""),
        border_radius=12,
        bgcolor="#1e293b",
    )
    password_input = ft.TextField(
        label="Пароль от ЛК",
        value=cfg.get("password", ""),
        password=True,
        can_reveal_password=True,
        border_radius=12,
        bgcolor="#1e293b",
    )
    interval_dropdown = ft.Dropdown(
        label="Интервал проверки",
        value=cfg.get("interval", "1"),
        border_radius=12,
        bgcolor="#1e293b",
        options=[
            ft.dropdown.Option("0.5", "Каждые 30 секунд (мгновенно)"),
            ft.dropdown.Option("1", "Каждую 1 минуту"),
            ft.dropdown.Option("5", "Каждые 5 минут"),
        ],
    )
    mode_dropdown = ft.Dropdown(
        label="Окно проверки (старт за 10 мин до пары)",
        value=cfg.get("mode", "full_lesson"),
        border_radius=12,
        bgcolor="#1e293b",
        options=[
            ft.dropdown.Option("full_lesson", "За 10 мин до пары + вся пара (95 мин)"),
            ft.dropdown.Option("always", "Постоянно 24/7 (без расписания)"),
            ft.dropdown.Option("first_30", "За 10 мин до пары + первые 30 мин"),
        ],
    )

    tg_token_input = ft.TextField(
        label="Telegram Bot Token (необязательно, для пушей)",
        value=cfg.get("tg_token", ""),
        border_radius=12,
        bgcolor="#1e293b",
        text_size=12,
    )
    tg_chat_input = ft.TextField(
        label="Telegram Chat ID (необязательно)",
        value=cfg.get("tg_chat_id", ""),
        border_radius=12,
        bgcolor="#1e293b",
        text_size=12,
    )

    status_text = ft.Text(
        "Статус: Отключено", size=14, weight=ft.FontWeight.BOLD, color="#94a3b8"
    )
    schedule_column = ft.Column(spacing=6)
    logs_view = ft.ListView(height=150, spacing=4, auto_scroll=True)

    state = {
        "active": False,
        "stop_event": threading.Event(),
        "marked_today": set(),
        "parsed_times": set(),
    }

    def show_in_app_banner(text: str) -> None:
        snack = ft.SnackBar(
            content=ft.Text(text, color="#ffffff", weight=ft.FontWeight.BOLD),
            bgcolor="#059669",
            duration=8000,
        )
        page.overlay.append(snack)
        snack.open = True
        page.update()

    def add_log(msg: str) -> None:
        stamp = dt.datetime.now().strftime("%H:%M:%S")
        logs_view.controls.append(
            ft.Text(f"[{stamp}] {msg}", size=11, font_family="monospace", color="#cbd5e1")
        )
        if len(logs_view.controls) > 80:
            logs_view.controls.pop(0)
        page.update()

    def notify_checkin(subject_title: str) -> None:
        now_str = dt.datetime.now().strftime("%H:%M:%S")
        notif_title = f"✅ Отметился на паре в {now_str}!"
        notif_body = f"Занятие: {subject_title}"
        add_log(f"{notif_title} ({subject_title})")
        show_in_app_banner(f"{notif_title}\n{subject_title}")
        send_phone_notification(
            title=notif_title,
            message=notif_body,
            tg_token=tg_token_input.value or "",
            tg_chat_id=tg_chat_input.value or "",
        )

    def update_lessons_ui(lessons: List[LessonItem]) -> None:
        schedule_column.controls.clear()
        if not lessons:
            schedule_column.controls.append(
                ft.Text("Активных пар в таблице пока не найдено.", size=12, color="#94a3b8")
            )
        else:
            for les in lessons:
                badge = (
                    "✅ Отмечен"
                    if les.already_checked
                    else ("⏳ Ждем кнопку" if not les.rasp_id else "🔥 Доступна отметка")
                )
                color = "#34d399" if les.already_checked else "#fbbf24"
                schedule_column.controls.append(
                    ft.Container(
                        padding=10,
                        border_radius=10,
                        bgcolor="#1e293b",
                        content=ft.Column(
                            [
                                ft.Text(
                                    f"{les.time_range} — {badge}",
                                    size=12,
                                    weight=ft.FontWeight.BOLD,
                                    color=color,
                                ),
                                ft.Text(les.subject, size=11, color="#e2e8f0"),
                            ],
                            spacing=2,
                        ),
                    )
                )
        page.update()

    def worker_loop(login: str, password: str, interval_min: float, mode: str) -> None:
        client = SutDirectClient(login, password)
        ok, msg = client.authenticate()
        add_log(msg)
        if not ok:
            state["active"] = False
            status_text.value = f"Ошибка: {msg}"
            status_text.color = "#f87171"
            toggle_btn.text = "ВКЛЮЧИТЬ АВТООТМЕТКУ"
            toggle_btn.bgcolor = "#2563eb"
            page.update()
            return

        current_day = dt.date.today()

        while not state["stop_event"].is_set():
            if dt.date.today() != current_day:
                current_day = dt.date.today()
                state["marked_today"].clear()

            win_info = check_lesson_window(mode, state["parsed_times"])
            if win_info is not None:
                status_text.value = f"Активно • {win_info}"
                status_text.color = "#34d399"
                page.update()

                lessons, checked, poll_msg = client.fetch_schedule_and_checkin(
                    state["marked_today"]
                )
                for les in lessons:
                    if les.start_time:
                        state["parsed_times"].add(les.start_time)

                update_lessons_ui(lessons)

                if checked:
                    for item in checked:
                        notify_checkin(item)
                elif poll_msg != "ОК":
                    add_log(poll_msg)
                else:
                    add_log("Проверено: кнопка «Начать занятие» еще не появилась.")

                state["stop_event"].wait(timeout=max(15, int(interval_min * 60)))
            else:
                status_text.value = "Ожидание (опрос начнется за 10 мин до пары)"
                status_text.color = "#fbbf24"
                page.update()
                state["stop_event"].wait(timeout=30)

    def on_toggle_click(e) -> None:
        if state["active"]:
            state["active"] = False
            state["stop_event"].set()
            toggle_btn.text = "ВКЛЮЧИТЬ АВТООТМЕТКУ"
            toggle_btn.bgcolor = "#2563eb"
            status_text.value = "Статус: Остановлено"
            status_text.color = "#94a3b8"
            add_log("Автоотметка остановлена.")
            page.update()
            return

        login = (login_input.value or "").strip()
        password = (password_input.value or "").strip()
        interval_str = interval_dropdown.value or "1"
        mode_str = mode_dropdown.value or "full_lesson"

        if not login or not password:
            status_text.value = "Заполните логин и пароль!"
            status_text.color = "#f87171"
            page.update()
            return

        save_config(
            login,
            password,
            interval_str,
            mode_str,
            tg_token_input.value or "",
            tg_chat_input.value or "",
        )
        state["active"] = True
        state["stop_event"].clear()
        toggle_btn.text = "ОСТАНОВИТЬ (РАБОТАЕТ)"
        toggle_btn.bgcolor = "#059669"
        status_text.value = "Подключение к lk.sut.ru..."
        status_text.color = "#34d399"
        page.update()

        threading.Thread(
            target=worker_loop,
            args=(login, password, float(interval_str), mode_str),
            daemon=True,
        ).start()

    def on_check_now_click(e) -> None:
        login = (login_input.value or "").strip()
        password = (password_input.value or "").strip()
        if not login or not password:
            add_log("Сначала введите логин и пароль!")
            return
        save_config(
            login,
            password,
            interval_dropdown.value or "1",
            mode_dropdown.value or "full_lesson",
            tg_token_input.value or "",
            tg_chat_input.value or "",
        )

        def single_check() -> None:
            add_log("Разовая проверка расписания и отметки...")
            client = SutDirectClient(login, password)
            ok, msg = client.authenticate()
            add_log(msg)
            if not ok:
                return
            lessons, checked, poll_msg = client.fetch_schedule_and_checkin(
                state["marked_today"]
            )
            update_lessons_ui(lessons)
            if checked:
                for item in checked:
                    notify_checkin(item)
            else:
                add_log(f"Проверка завершена ({poll_msg}). Найдено пар: {len(lessons)}")

        threading.Thread(target=single_check, daemon=True).start()

    toggle_btn = ft.ElevatedButton(
        text="ВКЛЮЧИТЬ АВТООТМЕТКУ",
        bgcolor="#2563eb",
        color="#ffffff",
        height=50,
        style=ft.ButtonStyle(shape=ft.RoundedRectangleBorder(radius=14)),
        on_click=on_toggle_click,
    )

    check_now_btn = ft.OutlinedButton(
        text="Проверить расписание и отметиться сейчас",
        height=44,
        style=ft.ButtonStyle(shape=ft.RoundedRectangleBorder(radius=14)),
        on_click=on_check_now_click,
    )

    page.add(
        ft.Container(
            padding=18,
            border_radius=20,
            bgcolor="#111827",
            content=ft.Column(
                [
                    ft.Text("Bonch Direct AutoCheck", size=20, weight=ft.FontWeight.BOLD),
                    ft.Text(
                        "Прямая автоотметка на lk.sut.ru с уведомлениями",
                        size=12,
                        color="#94a3b8",
                    ),
                    login_input,
                    password_input,
                    interval_dropdown,
                    mode_dropdown,
                    ft.ExpansionTile(
                        title=ft.Text("Уведомления в Telegram (по желанию)", size=13),
                        controls=[tg_token_input, tg_chat_input],
                    ),
                    ft.Column(
                        [toggle_btn, check_now_btn],
                        spacing=8,
                        horizontal_alignment=ft.CrossAxisAlignment.STRETCH,
                    ),
                    status_text,
                    ft.Divider(color="#1e293b"),
                    ft.Text("Расписание из ЛК:", size=13, weight=ft.FontWeight.BOLD),
                    schedule_column,
                    ft.Divider(color="#1e293b"),
                    ft.Text("Журнал событий:", size=13, weight=ft.FontWeight.BOLD),
                    ft.Container(
                        content=logs_view,
                        bgcolor="#090d16",
                        padding=10,
                        border_radius=12,
                    ),
                ],
                spacing=12,
            ),
        )
    )


# =====================================================================
# ВАРИАНТ 2: РЕЗЕРВНЫЙ ВЕБ-РЕЖИМ (ЕСЛИ ЗАПУЩЕНО В PYDROID 3 БЕЗ FLET)
# =====================================================================
WEB_STATE = {
    "active": False,
    "status": "Отключено",
    "logs": [],
    "notifications": [],
    "marked_today": set(),
    "parsed_times": set(),
    "stop_event": threading.Event(),
}


def web_add_log(msg: str) -> None:
    stamp = dt.datetime.now().strftime("%H:%M:%S")
    WEB_STATE["logs"].append(f"[{stamp}] {msg}")
    if len(WEB_STATE["logs"]) > 80:
        WEB_STATE["logs"] = WEB_STATE["logs"][-80:]
    logger.info("%s", msg)


def run_web_fallback(port: int = 8080) -> None:
    cfg = load_saved_config()

    html_page = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Bonch AutoCheck</title>
<style>
  body { margin:0; background:#090d16; color:#f8fafc; font-family:system-ui,sans-serif; display:flex; justify-content:center; padding:16px; }
  .card { width:100%; max-width:420px; background:#111827; border:1px solid #1e293b; border-radius:22px; padding:20px; box-sizing:border-box; }
  input, select { width:100%; box-sizing:border-box; padding:11px 13px; margin:6px 0 12px; border-radius:10px; border:1px solid #334155; background:#1e293b; color:#fff; font-size:14px; }
  .btn { width:100%; padding:14px; border-radius:12px; border:none; background:#2563eb; color:#fff; font-weight:700; font-size:15px; cursor:pointer; }
  .btn.on { background:#059669; }
  .toast { display:none; background:#059669; color:#fff; padding:12px; border-radius:12px; margin-bottom:12px; font-size:13px; font-weight:700; }
  .log { background:#090d16; border:1px solid #1e293b; border-radius:12px; padding:10px; font-family:monospace; font-size:11px; height:150px; overflow-y:auto; color:#cbd5e1; white-space:pre-wrap; }
</style>
</head>
<body>
<div class="card">
  <div id="toast" class="toast"></div>
  <div style="font-size:18px;font-weight:800;">Bonch Direct AutoCheck</div>
  <div style="font-size:12px;color:#94a3b8;margin-bottom:12px;">Старт за 10 мин до пары • Уведомления о времени отметки</div>
  <input id="login" placeholder="Логин / Email от lk.sut.ru" value="__LOGIN__">
  <input id="password" type="password" placeholder="Пароль от ЛК" value="__PASSWORD__">
  <select id="interval">
    <option value="0.5">Каждые 30 секунд</option>
    <option value="1" selected>Каждую 1 минуту</option>
    <option value="5">Каждые 5 минут</option>
  </select>
  <select id="mode">
    <option value="full_lesson" selected>За 10 мин до пары + вся пара (95 мин)</option>
    <option value="always">Постоянно 24/7</option>
    <option value="first_30">За 10 мин до пары + первые 30 мин</option>
  </select>
  <button id="btn" class="btn" onclick="toggle()">ВКЛЮЧИТЬ АВТООТМЕТКУ</button>
  <div id="status" style="margin:12px 0;font-size:13px;font-weight:600;color:#94a3b8;">Статус: Отключено</div>
  <div id="logs" class="log">Готов к работе...</div>
</div>
<script>
let lastNotifCount = 0;
function beep() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    osc.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.35);
  } catch(e) {}
}
async function toggle() {
  if ("Notification" in window && Notification.permission !== "granted") {
    try { await Notification.requestPermission(); } catch(e) {}
  }
  const login = document.getElementById('login').value.trim();
  const password = document.getElementById('password').value.trim();
  const interval = parseFloat(document.getElementById('interval').value);
  const mode = document.getElementById('mode').value;
  const r = await fetch('/api', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body:JSON.stringify({action:'toggle', login, password, interval, mode})
  });
  render(await r.json());
}
async function poll() {
  try {
    const r = await fetch('/api', {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({action:'status'})
    });
    render(await r.json());
  } catch(e) {}
}
function render(data) {
  const btn = document.getElementById('btn');
  const st = document.getElementById('status');
  const lg = document.getElementById('logs');
  const toast = document.getElementById('toast');
  if (data.active) {
    btn.className = 'btn on';
    btn.innerText = 'ОСТАНОВИТЬ (РАБОТАЕТ)';
    st.style.color = '#34d399';
  } else {
    btn.className = 'btn';
    btn.innerText = 'ВКЛЮЧИТЬ АВТООТМЕТКУ';
    st.style.color = '#94a3b8';
  }
  st.innerText = data.status || 'Отключено';
  if (data.logs && data.logs.length) lg.innerText = data.logs.join('\\n');
  if (data.notifications && data.notifications.length > lastNotifCount) {
    const latest = data.notifications[data.notifications.length - 1];
    lastNotifCount = data.notifications.length;
    toast.style.display = 'block';
    toast.innerText = latest;
    beep();
    if ("Notification" in window && Notification.permission === "granted") {
      new Notification("Bonch AutoCheck", { body: latest });
    }
  }
}
setInterval(poll, 2000);
poll();
</script>
</body>
</html>"""
    html_page = html_page.replace("__LOGIN__", cfg.get("login", "")).replace(
        "__PASSWORD__", cfg.get("password", "")
    )

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args) -> None:
            return

        def do_GET(self) -> None:
            raw = html_page.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
            if body.get("action") == "toggle":
                if WEB_STATE["active"]:
                    WEB_STATE["active"] = False
                    WEB_STATE["stop_event"].set()
                    WEB_STATE["status"] = "Остановлено"
                    web_add_log("Автоотметка остановлена.")
                else:
                    login = body.get("login", "").strip()
                    password = body.get("password", "").strip()
                    interval = float(body.get("interval", 1.0))
                    mode = body.get("mode", "full_lesson")
                    save_config(login, password, str(interval), mode)
                    WEB_STATE["active"] = True
                    WEB_STATE["stop_event"].clear()

                    def loop() -> None:
                        client = SutDirectClient(login, password)
                        ok, msg = client.authenticate()
                        web_add_log(msg)
                        if not ok:
                            WEB_STATE["active"] = False
                            WEB_STATE["status"] = msg
                            return
                        while not WEB_STATE["stop_event"].is_set():
                            win = check_lesson_window(mode, WEB_STATE["parsed_times"])
                            if win:
                                WEB_STATE["status"] = f"Активно • {win}"
                                lessons, checked, _ = client.fetch_schedule_and_checkin(
                                    WEB_STATE["marked_today"]
                                )
                                for les in lessons:
                                    if les.start_time:
                                        WEB_STATE["parsed_times"].add(les.start_time)
                                if checked:
                                    for item in checked:
                                        now_s = dt.datetime.now().strftime("%H:%M:%S")
                                        notif_msg = f"✅ Отметился на паре в {now_s}! ({item})"
                                        WEB_STATE["notifications"].append(notif_msg)
                                        web_add_log(notif_msg)
                                        send_phone_notification(
                                            f"✅ Отметился на паре в {now_s}!", item
                                        )
                                else:
                                    web_add_log("Проверено: кнопка еще не появилась.")
                                WEB_STATE["stop_event"].wait(
                                    timeout=max(15, int(interval * 60))
                                )
                            else:
                                WEB_STATE["status"] = "Ждем (старт за 10 мин до пары)"
                                WEB_STATE["stop_event"].wait(timeout=30)

                    threading.Thread(target=loop, daemon=True).start()

            raw = json.dumps(
                {
                    "active": WEB_STATE["active"],
                    "status": WEB_STATE["status"],
                    "logs": WEB_STATE["logs"],
                    "notifications": WEB_STATE["notifications"],
                },
                ensure_ascii=False,
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    url = f"http://127.0.0.1:{port}"
    logger.info("Запущен локальный интерфейс: %s", url)
    threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    if HAS_FLET:
        ft.app(target=flet_main)
    else:
        run_web_fallback(8080)
