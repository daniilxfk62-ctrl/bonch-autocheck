import datetime as dt
import json
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Set, Tuple

import flet as ft
import requests
from bs4 import BeautifulSoup

# =====================================================================
# АДРЕСА ЛК СПбГУТ (lk.sut.ru)
# =====================================================================
BASE_URL = "https://lk.sut.ru/cabinet"
LOGIN_PAGE_URL = f"{BASE_URL}/?login=yes"
AUTH_URL = f"{BASE_URL}/lib/autentificationok.php"
INI_URL = f"{BASE_URL}/ini.php"
SCHEDULE_URL = f"{BASE_URL}/project/cabinet/forms/raspisanie.php"

CONFIG_FILE = Path.home() / ".bonch_flet_config.json"

# Стандартное расписание начала пар в СПбГУТ
BELL_SCHEDULE = [
    "09:00",  # 1 пара (09:00 - 10:35)
    "10:45",  # 2 пара (10:45 - 12:20)
    "13:00",  # 3 пара (13:00 - 14:35)
    "14:45",  # 4 пара (14:45 - 16:20)
    "16:30",  # 5 пара (16:30 - 18:05)
    "18:15",  # 6 пара (18:15 - 19:50)
    "20:00",  # 7 пара
]


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
        """Корректное декодирование ответа lk.sut.ru (защита от кракозябр)."""
        for enc in ("utf-8", "windows-1251", resp.apparent_encoding or "utf-8"):
            try:
                text = resp.content.decode(enc)
                if any(word in text.lower() for word in ("расписание", "занят", "кабинет", "пароль", "присутств", "1")):
                    return text
            except Exception:
                continue
        return resp.text

    def authenticate(self) -> Tuple[bool, str]:
        if not self.login or not self.password:
            return False, "Введите логин и пароль"
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
                # Инициализируем PHP-сессию кабинета
                self.session.get(INI_URL, timeout=15)
                self.session.get(LOGIN_PAGE_URL, timeout=15)
                self.is_authenticated = True
                return True, f"Успешный вход в ЛК ({self.login})"

            self.is_authenticated = False
            return False, "Неверный логин или пароль от lk.sut.ru"
        except Exception as exc:
            self.is_authenticated = False
            return False, f"Ошибка сети: {exc}"

    def fetch_schedule_and_checkin(self, marked_ids: Set[str]) -> Tuple[List[LessonItem], List[str], str]:
        if not self.is_authenticated:
            ok, msg = self.authenticate()
            if not ok:
                return [], [], msg

        newly_checked: List[str] = []
        lessons: List[LessonItem] = []

        try:
            resp = self.session.post(SCHEDULE_URL, timeout=15)
            html = self._decode_response(resp)

            # Если сессия на сервере истекла, таблица расписания не вернется
            if len(html.strip()) < 50 or "autentification" in html.lower() or "<table" not in html.lower():
                ok, msg = self.authenticate()
                if not ok:
                    return [], [], msg
                resp = self.session.post(SCHEDULE_URL, timeout=15)
                html = self._decode_response(resp)

            soup = BeautifulSoup(html, "html.parser")
            open_zan_pattern = re.compile(
                r"open_zan\s*\(\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*\)"
            )

            for tr in soup.find_all("tr"):
                row_text = tr.get_text(" ", strip=True)
                if not row_text:
                    continue

                time_match = re.search(
                    r"([01]?\d|2[0-3]):([0-5]\d)\s*[-–—]\s*(?:[01]?\d|2[0-3]):[0-5]\d",
                    row_text,
                )
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

                # Если есть активная кнопка open_zan — жмем её и проверяем результат
                if rasp_id and week_id and not already:
                    if self.send_open_zan(rasp_id, week_id):
                        marked_ids.add(rasp_id)
                        already = True
                        newly_checked.append(
                            row_text[:70] if row_text else f"Занятие #{rasp_id}"
                        )

                if time_match or rasp_id or already:
                    lessons.append(
                        LessonItem(
                            num=str(len(lessons) + 1),
                            time_range=time_match.group(0) if time_match else "Занятие",
                            start_time=start_time or "09:00",
                            subject=row_text[:85],
                            rasp_id=rasp_id,
                            week_id=week_id,
                            already_checked=already or (rasp_id in marked_ids if rasp_id else False),
                        )
                    )

            return lessons, newly_checked, "ОК"
        except Exception as exc:
            return lessons, newly_checked, f"Ошибка опроса: {exc}"

    def send_open_zan(self, rasp_id: str, week_id: str) -> bool:
        try:
            payload = {"open": 1, "rasp": rasp_id, "week": week_id}
            r = self.session.post(SCHEDULE_URL, data=payload, params=payload, timeout=15)
            if r.status_code != 200:
                return False
            # Перепроверяем страницу после нажатия, пропала ли кнопка / появилась ли отметка
            verify_resp = self.session.post(SCHEDULE_URL, timeout=15)
            verify_html = self._decode_response(verify_resp).lower()
            return "присутств" in verify_html or f"open_zan({rasp_id}" not in verify_html
        except Exception:
            return False


# =====================================================================
# ГРАФИЧЕСКОЕ ПРИЛОЖЕНИЕ НА FLET
# =====================================================================
def load_saved_config() -> dict:
    try:
        if CONFIG_FILE.exists():
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {}


def save_config(login: str, password: str, interval: str, mode: str) -> None:
    try:
        CONFIG_FILE.write_text(
            json.dumps(
                {"login": login, "password": password, "interval": interval, "mode": mode},
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
    except Exception:
        pass


def main(page: ft.Page):
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
            ft.dropdown.Option("0.5", "Каждые 30 секунд"),
            ft.dropdown.Option("1", "Каждую 1 минуту"),
            ft.dropdown.Option("5", "Каждые 5 минут"),
        ],
    )
    mode_dropdown = ft.Dropdown(
        label="Когда проверять",
        value=cfg.get("mode", "full_lesson"),
        border_radius=12,
        bgcolor="#1e293b",
        options=[
            ft.dropdown.Option("full_lesson", "Всю пару (95 мин от начала)"),
            ft.dropdown.Option("always", "Постоянно (без привязки к звонкам)"),
            ft.dropdown.Option("first_30", "Только первые 30 минут пары"),
        ],
    )

    status_text = ft.Text("Статус: Отключено", size=14, weight=ft.FontWeight.BOLD, color="#94a3b8")
    schedule_column = ft.Column(spacing=6)
    logs_view = ft.ListView(height=150, spacing=4, auto_scroll=True)

    state = {
        "active": False,
        "stop_event": threading.Event(),
        "marked_today": set(),
        "parsed_times": set(),
    }

    def add_log(msg: str):
        stamp = dt.datetime.now().strftime("%H:%M:%S")
        logs_view.controls.append(
            ft.Text(f"[{stamp}] {msg}", size=11, font_family="monospace", color="#cbd5e1")
        )
        if len(logs_view.controls) > 80:
            logs_view.controls.pop(0)
        page.update()

    def update_lessons_ui(lessons: List[LessonItem]):
        schedule_column.controls.clear()
        if not lessons:
            schedule_column.controls.append(
                ft.Text("Пар с кнопкой отметки пока не найдено.", size=12, color="#94a3b8")
            )
        else:
            for les in lessons:
                badge = "✅ Отмечен" if les.already_checked else ("⏳ Ждем кнопку" if not les.rasp_id else "🔥 Доступна отметка")
                color = "#34d399" if les.already_checked else "#fbbf24"
                schedule_column.controls.append(
                    ft.Container(
                        padding=10,
                        border_radius=10,
                        bgcolor="#1e293b",
                        content=ft.Column(
                            [
                                ft.Text(f"{les.time_range} — {badge}", size=12, weight=ft.FontWeight.BOLD, color=color),
                                ft.Text(les.subject, size=11, color="#e2e8f0"),
                            ],
                            spacing=2,
                        ),
                    )
                )
        page.update()

    def is_in_lesson_window(mode: str) -> Optional[str]:
        if mode == "always":
            return "Постоянный режим"
        window_min = 30 if mode == "first_30" else 95
        now = dt.datetime.now()
        all_times = sorted(set(BELL_SCHEDULE) | state["parsed_times"])
        for t_str in all_times:
            try:
                hh, mm = map(int, t_str.split(":"))
                start_dt = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                diff_min = (now - start_dt).total_seconds() / 60.0
                if -2.0 <= diff_min <= window_min:
                    return f"{t_str} (идет {max(0, int(diff_min))} мин)"
            except Exception:
                continue
        return None

    def worker_loop(login: str, password: str, interval_min: float, mode: str):
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

            win_info = is_in_lesson_window(mode)
            if win_info is not None:
                status_text.value = f"Активно • {win_info}"
                status_text.color = "#34d399"
                page.update()

                lessons, checked, poll_msg = client.fetch_schedule_and_checkin(state["marked_today"])
                for les in lessons:
                    if les.start_time:
                        state["parsed_times"].add(les.start_time)

                update_lessons_ui(lessons)

                if checked:
                    for item in checked:
                        add_log(f"✅ ОТМЕЧЕН: {item}")
                elif poll_msg != "ОК":
                    add_log(poll_msg)
                else:
                    add_log("Проверено: новых кнопок «Отметиться» пока нет.")

                state["stop_event"].wait(timeout=max(15, int(interval_min * 60)))
            else:
                status_text.value = "Ожидание начала следующей пары..."
                status_text.color = "#fbbf24"
                page.update()
                state["stop_event"].wait(timeout=30)

    def on_toggle_click(e):
        if state["active"]:
            state["active"] = False
            state["stop_event"].set()
            toggle_btn.text = "ВКЛЮЧИТЬ АВТООТМЕТКУ"
            toggle_btn.bgcolor = "#2563eb"
            status_text.value = "Статус: Остановлено"
            status_text.color = "#94a3b8"
            add_log("Автоотметка остановлена пользователем.")
            page.update()
            return

        login = login_input.value.strip()
        password = password_input.value.strip()
        interval_str = interval_dropdown.value or "1"
        mode_str = mode_dropdown.value or "full_lesson"

        if not login or not password:
            status_text.value = "Заполните логин и пароль!"
            status_text.color = "#f87171"
            page.update()
            return

        save_config(login, password, interval_str, mode_str)
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

    def on_check_now_click(e):
        login = login_input.value.strip()
        password = password_input.value.strip()
        if not login or not password:
            add_log("Сначала введите логин и пароль!")
            return
        save_config(login, password, interval_dropdown.value or "1", mode_dropdown.value or "full_lesson")

        def single_check():
            add_log("Разовая проверка расписания и отметки...")
            client = SutDirectClient(login, password)
            ok, msg = client.authenticate()
            add_log(msg)
            if not ok:
                return
            lessons, checked, poll_msg = client.fetch_schedule_and_checkin(state["marked_today"])
            update_lessons_ui(lessons)
            if checked:
                for c in checked:
                    add_log(f"✅ ОТМЕЧЕН: {c}")
            else:
                add_log(f"Проверка завершена ({poll_msg}). Найдено строк: {len(lessons)}")

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
        text="Проверить расписание сейчас",
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
                    ft.Text("Прямая автоотметка на lk.sut.ru", size=12, color="#94a3b8"),
                    login_input,
                    password_input,
                    interval_dropdown,
                    mode_dropdown,
                    ft.Column([toggle_btn, check_now_btn], spacing=8, horizontal_alignment=ft.CrossAxisAlignment.STRETCH),
                    status_text,
                    ft.Divider(color="#1e293b"),
                    ft.Text("Текущее расписание:", size=13, weight=ft.FontWeight.BOLD),
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


if __name__ == "__main__":
    ft.app(target=main)
