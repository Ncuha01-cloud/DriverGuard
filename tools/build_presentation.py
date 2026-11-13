"""Сборка презентации DriverGuard.pptx (16:9, 14 слайдов).

Стиль взят из ранее сделанной презентации проекта: тёмный фон, бирюзовый акцент,
круглые «чипы» технологий, нумерация слайдов NN / 14.
"""
import os
from PIL import Image
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.dml import MSO_LINE

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
SHOTS = os.path.join(PROJECT, "docs", "screenshots")
OUT = os.path.join(os.path.dirname(PROJECT), "DriverGuard-presentation.pptx")

REPO = "https://github.com/Ncuha01-cloud/DriverGuard"

# --- палитра (совпадает с ресурсами приложения) ---
BG = RGBColor(0x08, 0x0B, 0x12)
CARD = RGBColor(0x14, 0x1B, 0x2B)
STROKE = RGBColor(0x1E, 0x2A, 0x3F)
INK = RGBColor(0xF2, 0xF6, 0xFF)
INK2 = RGBColor(0x9A, 0xAB, 0xC9)
INK3 = RGBColor(0x5E, 0x6C, 0x86)
ACCENT = RGBColor(0x22, 0xD3, 0xEE)
OK = RGBColor(0x22, 0xE3, 0xA0)
WARN = RGBColor(0xFF, 0xB0, 0x20)
DANGER = RGBColor(0xFF, 0x4D, 0x6D)
VIOLET = RGBColor(0xA7, 0x8B, 0xFA)

FONT = "Arial"
FONT_L = "Arial"

TOTAL = 14
prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]


# ------------------------------------------------------------------ helpers
def add_slide():
    return prs.slides.add_slide(BLANK)


def rect(slide, x, y, w, h, fill=None, line=None, shape=MSO_SHAPE.RECTANGLE,
         line_w=1.0, radius=None):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = fill
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = line
        s.line.width = Pt(line_w)
    s.shadow.inherit = False
    if radius is not None and shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        try:
            s.adjustments[0] = radius
        except Exception:
            pass
    s.text_frame.word_wrap = True
    return s


def text(slide, x, y, w, h, runs, size=14, color=INK, bold=False, align=PP_ALIGN.LEFT,
         font=FONT, line_spacing=None, space_after=0, anchor=MSO_ANCHOR.TOP,
         letter=None):
    """runs: str | list[str] | list[list[(text, dict)]] — по абзацам."""
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    paras = runs if isinstance(runs, list) else [runs]
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        if line_spacing:
            p.line_spacing = line_spacing
        p.space_after = Pt(space_after)
        chunks = para if isinstance(para, list) else [(para, {})]
        for t, ov in chunks:
            r = p.add_run()
            r.text = t
            f = r.font
            f.name = ov.get("font", font)
            f.size = Pt(ov.get("size", size))
            f.bold = ov.get("bold", bold)
            f.color.rgb = ov.get("color", color)
            if "link" in ov:
                f.color.rgb = ov.get("color", ACCENT)
                r.hyperlink.address = ov["link"]
                f.underline = False
                f.color.rgb = ov.get("color", ACCENT)
            if letter is not None or "letter" in ov:
                # python-pptx не умеет spc напрямую — пишем в XML
                spc = ov.get("letter", letter)
                r._r.get_or_add_rPr().set("spc", str(int(spc * 100)))
    return tb


def bg(slide, glow=True):
    rect(slide, 0, 0, 13.333, 7.5, fill=BG)
    if glow:
        g = rect(slide, 8.6, -3.6, 8.4, 8.4, fill=RGBColor(0x0D, 0x1B, 0x28),
                 shape=MSO_SHAPE.OVAL)
        g2 = rect(slide, -4.0, 4.2, 7.6, 7.6, fill=RGBColor(0x0B, 0x14, 0x1F),
                  shape=MSO_SHAPE.OVAL)


def header(slide, num, section, kicker=None):
    """Верхняя плашка: номер-чип, раздел, справа тема; нижняя линия + колонтитул."""
    chip = rect(slide, 0.65, 0.42, 0.30, 0.30, line=ACCENT, line_w=1.25,
                shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.28)
    text(slide, 0.65, 0.47, 0.30, 0.22, str(num), size=10, color=ACCENT,
         bold=True, align=PP_ALIGN.CENTER)
    text(slide, 1.06, 0.46, 4.2, 0.24, section.upper(), size=10, color=INK3,
         bold=True, letter=1.8)
    if kicker:
        text(slide, 7.6, 0.47, 5.08, 0.24, kicker, size=10, color=INK3,
             align=PP_ALIGN.RIGHT, letter=0.6)
    rect(slide, 0.65, 6.93, 10.15, 0.012, fill=STROKE)
    text(slide, 10.95, 6.82, 1.73, 0.26, "DriverGuard", size=9.5, color=INK3,
         align=PP_ALIGN.RIGHT)
    text(slide, 11.98, 6.82, 0.70, 0.26, f"{num:02d} / {TOTAL}", size=9.5,
         color=ACCENT, align=PP_ALIGN.RIGHT, bold=True)


def title(slide, y, runs, size=34, color=INK, w=11.9, x=0.65):
    text(slide, x, y, w, 0.9, runs, size=size, color=color, bold=True,
         line_spacing=1.02)


def accent_line(slide, x, y, w=0.26, color=ACCENT):
    rect(slide, x, y, w, 0.035, fill=color)


def bullet_block(slide, x, y, w, items, size=13.5, color=INK2, gap=0.30,
                 bullet_color=ACCENT, dot=True):
    """Простые пункты: маркер-точка + текст (полужирный лид до ' — ')."""
    cy = y
    for it in items:
        d = rect(slide, x, cy + 0.085, 0.085, 0.085, fill=bullet_color,
                 shape=MSO_SHAPE.OVAL)
        txt = it
        if " — " in it and "**" not in it:
            head, tail = it.split(" — ", 1)
            runs = [[(head, {"bold": True, "color": INK}), (" — " + tail, {})]]
        else:
            runs = [[(it, {})]]
        text(slide, x + 0.24, cy, w - 0.24, gap, runs, size=size, color=color,
             line_spacing=1.06)
        cy += gap + 0.08
    return cy


def card(slide, x, y, w, h, fill=CARD, stroke_=STROKE):
    return rect(slide, x, y, w, h, fill=fill, line=stroke_,
                shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.06)


def chip(slide, x, y, label, color=ACCENT, w=None, size=9.5, h=0.30):
    w = w or (0.17 * len(label) + 0.34)
    c = rect(slide, x, y, w, h, line=RGBColor(0x25, 0x38, 0x52),
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.45)
    text(slide, x, y + 0.07, w, 0.2, label, size=size, color=color,
         align=PP_ALIGN.CENTER)
    return w


def picture_rounded(slide, path, x, y, h, border=RGBColor(0x25, 0x38, 0x52),
                    pad=0.035, frame=True):
    """Картинка с рамкой: пропорции 1080x2400."""
    w = h * 1080 / 2400
    if frame:
        rect(slide, x - pad, y - pad, w + 2 * pad, h + 2 * pad,
             fill=RGBColor(0x0F, 0x16, 0x24), line=border, line_w=1.0,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, radius=0.05)
    slide.shapes.add_picture(path, Inches(x), Inches(y), height=Inches(h))
    return w


def shot(name):
    return os.path.join(SHOTS, name)


# ================================================================== 1. Титул
s = add_slide()
bg(s)
rect(s, 0, 0, 13.333, 0.05, fill=ACCENT)
accent_line(s, 0.75, 1.62, 0.30)
text(s, 1.19, 1.50, 7.4, 0.26,
     "ТРЕКЕР ГЛАЗ ДЛЯ ВОДИТЕЛЯ · ANDROID · OPEN SOURCE", size=10, color=ACCENT,
     bold=True, letter=2.2)
text(s, 0.75, 1.95, 8.2, 1.7,
     [[("Driver", {"color": INK}), ("Guard", {"color": ACCENT})]],
     size=64, bold=True, font=FONT_L)
text(s, 0.75, 3.50, 6.6, 1.0,
     "Камера смотрит на лицо каждую секунду пути и поднимает тревогу "
     "за 1,1 секунды до того, как закрытые глаза станут аварией.",
     size=15.5, color=INK2, line_spacing=1.22)

cx = 0.75
for label, col in [("Android 8+", INK2), ("Kotlin", ACCENT), ("ML Kit", ACCENT),
                   ("CameraX", ACCENT), ("Без сети", OK)]:
    cx += chip(s, cx, 4.72, label, color=col) + 0.11

text(s, 0.75, 5.62, 6.6, 0.3,
     [[("2026  ·  версия 1.0.0  ·  ", {}), (REPO.replace("https://", ""), {"color": ACCENT})]],
     size=10.5, color=INK3, letter=0.5)

picture_rounded(s, shot("05_settings.png"), 9.10, 0.70, 6.10)
# подпись-плашка у телефона
rect(s, 8.95, 6.20, 3.55, 0.0)  # spacer (сохраняет сетку свободной)

# ================================================================== 2. Что за проект
s = add_slide()
bg(s)
header(s, 2, "Проект", "Что именно сделано")
title(s, 0.98, [[("Что такое ", {}), ("DriverGuard", {"color": ACCENT})]], size=32)
text(s, 0.65, 1.62, 6.35, 0.95,
     "Android-приложение, которое непрерывно оценивает состояние водителя по "
     "видео с фронтальной камеры телефона и будит его, если он начинает засыпать.",
     size=14, color=INK2, line_spacing=1.2)

facts = [
    ("4", "признака усталости", "глаза, кивки, зевота, накопленный балл"),
    ("1,1 с", "до тревоги", "столько закрытых глаз хватает для сигнала"),
    ("100%", "расчётов на устройстве", "без сети, видео не покидает телефон"),
    ("0 МБ", "трафика", "нет аналитики и загрузки модели в облаке"),
]
fx = 0.65
for big, mid, small in facts:
    card(s, fx, 2.78, 1.50, 1.36)
    text(s, fx + 0.14, 2.92, 1.25, 0.44, big, size=21, color=ACCENT, bold=True)
    text(s, fx + 0.14, 3.40, 1.22, 0.34, mid, size=9.5, color=INK, bold=True,
         line_spacing=1.0)
    text(s, fx + 0.14, 3.82, 1.22, 0.34, small, size=7.5, color=INK3,
         line_spacing=1.0)
    fx += 1.62

card(s, 0.65, 4.38, 6.35, 1.40)
text(s, 0.90, 4.54, 5.9, 0.26, "ГОТОВО К ИСПОЛЬЗОВАНИЮ", size=9, color=ACCENT,
     bold=True, letter=1.6)
text(s, 0.90, 4.84, 5.9, 0.85,
     "Собранный APK, калибровка под посадку, настройки чувствительности, "
     "история поездок, экран тревоги и открытый исходный код на GitHub.",
     size=12.5, color=INK2, line_spacing=1.14)

steps = [
    ("01", "Камера", "CameraX, 480×640 — только кадры, без записи"),
    ("02", "Анализ", "ML Kit: лицо, контуры глаз и губ, углы головы"),
    ("03", "Решение", "ядро считает EAR, кивки и зевоту"),
    ("04", "Реакция", "уведомление или сирена с вибрацией"),
]
sy = 4.38
for num, head, desc in steps:
    card(s, 7.25, sy, 5.43, 0.96)
    text(s, 7.42, sy + 0.16, 0.40, 0.24, num, size=10, color=ACCENT, bold=True)
    text(s, 7.92, sy + 0.14, 4.5, 0.24, head, size=11, color=INK, bold=True)
    text(s, 7.92, sy + 0.46, 4.55, 0.40, desc, size=9.5, color=INK2, line_spacing=1.06)
    sy += 0.60

# ================================================================== 3. Проблема
s = add_slide()
bg(s)
header(s, 3, "Проблема", "Почему это важно")
title(s, 0.98, "Засыпание за рулём — самое незаметное опасное состояние",
      size=29)
text(s, 0.65, 1.66, 6.35, 0.9,
     "Человек не замечает момент, когда засыпает: ощущение «я в порядке» "
     "остаётся ещё долго после того, как внимание уже ушло.",
     size=14, color=INK2, line_spacing=1.2)


def big_number(slide, x, y, value, label, color, w=2.9):
    card(slide, x, y, w, 1.42)
    text(slide, x + 0.20, y + 0.18, w - 0.4, 0.6, value, size=30, color=color,
         bold=True, font=FONT_L)
    text(slide, x + 0.20, y + 0.86, w - 0.4, 0.44, label, size=9.5, color=INK2,
         line_spacing=1.05)


big_number(s, 0.65, 2.80, "1–4 с", "длится микро-сон", DANGER, w=2.02)
big_number(s, 2.79, 2.80, "≈100 м", "успевает проехать машина\nна 90 км/ч", WARN,
           w=2.02)
big_number(s, 4.93, 2.80, "2 с", "нужно, чтобы среагировать —\nесли предупреждение пришло "
           "заранее", OK, w=2.07)

card(s, 0.65, 4.48, 6.35, 2.30, fill=RGBColor(0x0F, 0x1B, 0x29))
text(s, 0.90, 4.66, 4.4, 0.24, "ПОЧЕМУ ТЕКУЩИЕ РЕШЕНИЯ НЕ ПОМОГАЮТ",
     size=9, color=ACCENT, bold=True, letter=0.9)
bullet_block(s, 0.90, 5.02, 5.90, [
    "Самоконтроль — не работает: бодрость ощущается, но не совпадает "
    "с реальной.",
    "Кофе и музыка — помогают коротко и в итоге обманывают сильнее.",
    "Встроенные системы — есть только в новых дорогих авто.",
], size=10, gap=0.42)

# правая колонка — иллюстрация «что видит камера»
card(s, 7.35, 0.98, 5.33, 5.62)
text(s, 7.60, 1.16, 4.9, 0.26, "ЧТО УСПЕВАЕТ ПРОВЕРИТЬ ПРИЛОЖЕНИЕ", size=9,
     color=ACCENT, bold=True, letter=1.4)
rows = [
    ("Глаза прикрыты", "EAR падает ниже 0,13", WARN),
    ("Голова опускается", "наклон > 21° дольше 1,5 с", WARN),
    ("Рот открылся", "зевота, ratio > 0,42", VIOLET),
    ("Глаза закрыты > 1,1 с", "тревога уровня 3", DANGER),
]
ry = 1.58
for head, desc, col in rows:
    rect(s, 7.60, ry + 0.06, 0.10, 0.62, fill=col)
    text(s, 7.86, ry, 4.6, 0.26, head, size=12, color=INK, bold=True)
    text(s, 7.86, ry + 0.30, 4.6, 0.28, desc, size=10, color=INK2)
    ry += 0.86
rect(s, 7.60, 5.06, 4.83, 0.014, fill=STROKE)
text(s, 7.60, 5.20, 4.83, 1.2,
     "Все эти признаки видны по обычной фронтальной камере — отдельное "
     "оборудование не требуется.",
     size=11.5, color=INK2, line_spacing=1.16)

# ================================================================== 4. Альтернативы
s = add_slide()
bg(s)
header(s, 4, "Рынок", "С чем сравниваем")
title(s, 0.98, "Альтернативы", size=32)
text(s, 0.65, 1.58, 11.9, 0.30,
     "Пять способов не уснуть за рулём — и почему каждый из них не решает "
     "задачу обычного водителя целиком.", size=12.5, color=INK2)

alts = [
    ("01", "Самоконтроль, кофе, музыка", "Ничего не стоит",
     "Субъективная оценка обманывает; эффект кофе заканчивается "
     "внезапным «провалом».", "Слабость"),
    ("02", "Стандартные Dashcam", "Дешёвые",
     "Пишут видео, но никого не предупреждают — разбор только после ДТП.",
     "Слабость"),
    ("03", "Встроенный DMS в новых авто", "Точный, заводской",
     "Только в дорогих комплектациях и новых машинах; пороги не настроить.",
     "Слабость"),
    ("04", "Научные прототипы на Python/DL", "Гибкие, точные",
     "Нужны ПК, часто GPU и постоянное подключение; не ездят в кармане.",
     "Слабость"),
    ("05", "Платформы мониторинга автопарков", "Контроль и отчёты",
     "Ориентированы на B2B: абонплата, установка, данные уходят на сервер.",
     "Слабость"),
]
ax = 0.65
for num, name, plus, minus, tag in alts:
    card(s, ax, 2.05, 2.28, 3.95)
    text(s, ax + 0.20, 2.22, 1.9, 0.24, num, size=10, color=ACCENT, bold=True)
    text(s, ax + 0.20, 2.52, 1.90, 0.76, name, size=12.5, color=INK, bold=True,
         line_spacing=1.06)
    rect(s, ax + 0.20, 3.36, 0.24, 0.028, fill=OK)
    text(s, ax + 0.20, 3.46, 1.90, 0.62, plus, size=9, color=OK,
         line_spacing=1.08)
    rect(s, ax + 0.20, 4.18, 0.24, 0.028, fill=DANGER)
    text(s, ax + 0.20, 4.28, 1.90, 1.30, minus, size=9, color=INK2,
         line_spacing=1.08)
    ax += 2.36

card(s, 0.65, 6.20, 11.9, 0.58, fill=RGBColor(0x0F, 0x1B, 0x29))
text(s, 0.90, 6.33, 11.4, 0.34,
     [[("Вывод: ", {"color": ACCENT, "bold": True}),
       ("нужен прибор, который уже в кармане, работает офлайн и говорит "
        "именно в тот момент, когда человек перестаёт контролировать себя.", {})]],
     size=11.5, color=INK2)

# ================================================================== 5. Превосходство
s = add_slide()
bg(s)
header(s, 5, "Преимущества", "Где мы сильнее")
title(s, 0.98, [[("В чём DriverGuard ", {}), ("превосходит", {"color": ACCENT}),
                 (" альтернативы", {})]], size=31)

wins = [
    (OK, "Обычный смартфон", "С нулевой стоимостью оборудования: камера, "
     "держатель и телефон, который уже есть."),
    (OK, "Полностью офлайн", "Модель ML Kit встроена в APK; видео не "
     "записывается и не отправляется."),
    (OK, "Работает как будильник", "Сирена поднимает громкость до максимума "
     "будильника, экран перекрывает блокировку."),
    (ACCENT, "Настраивается под водителя", "Калибровка посадки и один ползунок "
     "чувствительности вместо таблицы порогов."),
    (ACCENT, "Объяснимое ядро", "EAR, углы и таймеры — без «чёрного ящика»; "
     "ядро на чистом Kotlin удобно тестировать."),
    (ACCENT, "Открытый код", "MIT, весь исходник и скриншоты на GitHub — "
     "можно собрать и доработать самому."),
]
wx, wy = 0.65, 1.78
for i, (col, head, desc) in enumerate(wins):
    x = wx + (i % 3) * 4.02
    y = wy + (i // 3) * 2.42
    card(s, x, y, 3.82, 2.16)
    d = rect(s, x + 0.22, y + 0.24, 0.34, 0.34, fill=col, shape=MSO_SHAPE.OVAL)
    text(s, x + 0.22, y + 0.335, 0.34, 0.2, "✓", size=11, color=BG, bold=True,
         align=PP_ALIGN.CENTER)
    text(s, x + 0.70, y + 0.26, 2.95, 0.5, head, size=12.5, color=INK,
         bold=True, line_spacing=1.05)
    text(s, x + 0.22, y + 0.86, 3.40, 1.16, desc, size=10, color=INK2,
         line_spacing=1.14)

# ================================================================== 6. Ограничения
s = add_slide()
bg(s)
header(s, 6, "Честный разговор", "Где сильнее конкуренты")
title(s, 0.98, [[("В чём ", {}), ("альтернативы превосходят", {"color": WARN}),
                 (" нас", {})]], size=31)
text(s, 0.65, 1.60, 7.0, 0.32,
     "Признаём ограничения прямо: это open-source учебный проект, "
     "а не сертифицированное устройство.", size=12.5, color=INK2)

losses = [
    ("Мультимодальность", "Встроенные DMS смотрят ещё и на дорогу, руль и "
     "траекторию. Мы видим только лицо."),
    ("Надёжность в любых условиях", "Ночь, низкая контрастность и "
     "солнцезащитные очки снижают точность EAR."),
    ("Железо и стабильность", "Телефон в держателе трясётся, камера греется, "
     "лицо может уходить из кадра."),
    ("Только Android", "Версии для iOS нет; iPhone-владельцам решение "
     "не подойдёт."),
    ("Нет сертификации", "Не медицинское изделие и не часть ADAS: "
     "не заменяет отдых и внимательность."),
    ("Один водитель на устройство", "Учётной записи и облачной синхронизации "
     "нет — история видна только в приложении."),
]
lx, ly = 0.65, 2.22
for i, (head, desc) in enumerate(losses):
    x = lx + (i % 2) * 4.30
    y = ly + (i // 2) * 1.44
    card(s, x, y, 4.10, 1.26, fill=RGBColor(0x17, 0x18, 0x22),
         stroke_=RGBColor(0x33, 0x22, 0x2C))
    rect(s, x + 0.20, y + 0.20, 0.10, 0.86, fill=WARN)
    text(s, x + 0.44, y + 0.16, 3.45, 0.26, head, size=11.5, color=INK,
         bold=True)
    text(s, x + 0.44, y + 0.46, 3.48, 0.72, desc, size=9.5, color=INK2,
         line_spacing=1.10)

card(s, 0.65, 6.42, 11.9, 0.0)

# ================================================================== 7. Как работает
s = add_slide()
bg(s)
header(s, 7, "Технология", "Пайплайн кадра")
title(s, 0.98, [[("Как это ", {}), ("работает", {"color": ACCENT}),
                 (" внутри", {})]], size=32)
text(s, 0.65, 1.58, 8.0, 0.30,
     "Один кадр фронтальной камеры проходит четыре шага, и на каждом есть "
     "причина, почему сделано именно так.", size=12.5, color=INK2)

pipe = [
    ("CameraX", "фронтальная камера\n480×640, YUV → Bitmap", ACCENT),
    ("ML Kit", "контуры глаз и губ\nуглы Эйлера головы", VIOLET),
    ("DrowsinessEngine", "EAR, кивки, зевота\nscore усталости 0..1", OK),
    ("MonitoringService", "уведомление → сирена\nэкран тревоги", DANGER),
]
px = 0.65
for i, (head, desc, col) in enumerate(pipe):
    card(s, px, 2.14, 2.52, 1.42)
    rect(s, px, 2.14, 2.52, 0.05, fill=col)
    text(s, px + 0.18, 2.32, 2.2, 0.28, head, size=11, color=INK, bold=True)
    text(s, px + 0.18, 2.64, 2.2, 0.62, desc, size=9, color=INK2, line_spacing=1.12)
    if i < 3:
        text(s, px + 2.56, 2.66, 0.30, 0.3, "→", size=16, color=INK3)
    px += 2.90

text(s, 0.65, 3.86, 5.9, 0.26, "ТРИ ПРИЗНАКА — ТРИ НЕЗАВИСИМЫХ ТАЙМЕРА",
     size=9, color=ACCENT, bold=True, letter=1.4)
sig = [
    ("ГЛАЗА", "EAR = высота контура / ширина", "закрыт < 0,13 · открыт > 0,17",
     "тревога через 1,1 с"),
    ("ГОЛОВА", "угол Эйлера X минус калибровка", "> 21° относительно нейтра",
     "тревога через 1,5 с"),
    ("РОТ", "высота рта / ширина лица", "> 0,42 — зевота", "предупреждение"),
]
sx = 0.65
for head, formula, thr, react in sig:
    card(s, sx, 4.18, 1.94, 1.62)
    text(s, sx + 0.16, 4.30, 1.65, 0.24, head, size=9, color=ACCENT, bold=True,
         letter=1.2)
    text(s, sx + 0.16, 4.58, 1.64, 0.60, formula, size=8.5, color=INK2,
         line_spacing=1.08)
    text(s, sx + 0.16, 5.18, 1.64, 0.24, thr, size=7.5, color=INK, bold=True,
         line_spacing=1.0)
    text(s, sx + 0.16, 5.48, 1.64, 0.22, react, size=7.5, color=INK3,
         line_spacing=1.0)
    sx += 2.02

text(s, 0.65, 5.98, 6.3, 0.26, "ЗАЩИТА ОТ ЛОЖНЫХ ТРЕВОГ", size=9, color=WARN,
     bold=True, letter=1.4)
bullet_block(s, 0.65, 6.26, 6.3, [
    "Гистерезис: закрытие и открытие оцениваются по разным порогам.",
    "Потеря лица дольше 2,5 с (окно, зеркало) не считается усталостью.",
], size=9.5, gap=0.34, bullet_color=WARN)

card(s, 7.35, 3.86, 5.33, 2.74)
text(s, 7.60, 4.02, 4.9, 0.26, "УРОВНИ РЕАКЦИИ", size=9, color=ACCENT, bold=True,
     letter=1.4)
levels = [
    ("1", "Предупреждение", "прикрытые глаза, зевок, начало кивка", WARN),
    ("2", "Опасность", "долгая зевота, микро-сон", WARN),
    ("3", "Тревога", "сирена 880/1320 Гц, вибрация, экран «ПРОСНИСЬ»", DANGER),
]
ly2 = 4.36
for n, head, desc, col in levels:
    rect(s, 7.60, ly2, 0.36, 0.36, fill=col, shape=MSO_SHAPE.ROUNDED_RECTANGLE,
         radius=0.3)
    text(s, 7.60, ly2 + 0.075, 0.36, 0.22, n, size=10.5, color=BG, bold=True,
         align=PP_ALIGN.CENTER)
    text(s, 8.08, ly2, 4.4, 0.24, head, size=11, color=INK, bold=True)
    text(s, 8.08, ly2 + 0.28, 4.4, 0.3, desc, size=9, color=INK2)
    ly2 += 0.66

# ================================================================== 8. Алгоритм EAR
s = add_slide()
bg(s)
header(s, 8, "Технология", "Одно число вместо «чёрного ящика»")
title(s, 0.98, "EAR: как программа понимает, что глаза закрыты", size=29)
text(s, 0.65, 1.60, 6.1, 0.6,
     "ML Kit отдаёт контур глаза точками. Отношение «высоты» контура к его "
     "ширине — устойчивый признак: у открытого глаза ~0,3, у закрытого почти 0.",
     size=13, color=INK2, line_spacing=1.18)

steps_ear = [
    ("1", "Берём 4 точки контура", "внешний и внутренний углы, плюс две "
     "точки на верхнем и нижнем веке"),
    ("2", "Считаем высоту по нормали", "к линии, соединяющей углы глаза — "
     "это не даёт наклону головы искажать измерение"),
    ("3", "Делим на ширину", "получаем сырое отношение, не зависящее от "
     "разрешения камеры и дистанции"),
    ("4", "Нормируем адаптивно", "минимум и максимум подстраиваются под "
     "лицо в первые ~10 секунд поездки"),
]
ey = 2.36
for n, head, desc in steps_ear:
    circle = rect(s, 0.65, ey, 0.34, 0.34, line=ACCENT, line_w=1.2,
                  shape=MSO_SHAPE.OVAL)
    text(s, 0.65, ey + 0.065, 0.34, 0.22, n, size=10, color=ACCENT, bold=True,
         align=PP_ALIGN.CENTER)
    text(s, 1.14, ey - 0.04, 5.55, 0.26, head, size=11.5, color=INK, bold=True)
    text(s, 1.14, ey + 0.26, 5.55, 0.62, desc, size=9.5, color=INK2,
         line_spacing=1.10)
    ey += 1.02

# правая карточка с формулой
card(s, 7.15, 1.60, 5.53, 2.30)
text(s, 7.42, 1.78, 5.0, 0.26, "ФОРМУЛА", size=9, color=ACCENT, bold=True,
     letter=1.6)
text(s, 7.42, 2.08, 5.0, 0.40,
     [[("EAR  =  (h₁ + h₂) / 2 ÷  w", {"size": 20, "bold": True, "color": INK})]],
     size=20)
text(s, 7.42, 2.62, 5.0, 1.1,
     [[("h₁, h₂", {"bold": True, "color": ACCENT}),
       (" — расстояния от век до линии между углами глаза, ", {}),
       ("w", {"bold": True, "color": ACCENT}),
       (" — ширина глаза по этой линии.", {})]],
     size=10.5, color=INK2, line_spacing=1.18)

card(s, 7.15, 4.06, 5.53, 2.54)
text(s, 7.42, 4.24, 5.0, 0.26, "ПОЧЕМУ НЕ ВСТРОЕННАЯ ВЕРОЯТНОСТЬ ML KIT",
     size=9, color=WARN, bold=True, letter=1.2)
bullet_block(s, 7.42, 4.56, 5.0, [
    "Классификация открытых глаз у ML Kit рассчитана на крупное фронтальное "
    "лицо и нестабильна в машине.",
    "Геометрия по контуру предсказуема и объяснима — видно, из чего вышло "
    "решение.",
    "Если вероятность всё же доступна, она мягко подмешивается к геометрии, "
    "но не заменяет её.",
], size=9.5, gap=0.62, bullet_color=WARN)

# ================================================================== 9. Приватность
s = add_slide()
bg(s)
header(s, 9, "Приватность", "Данные и доверие")
title(s, 0.98, "Всё считается на телефоне", size=32)
text(s, 0.65, 1.62, 6.0, 0.6,
     "Камера водителя — чувствительные данные. Поэтому сети, аккаунтов и "
     "аналитики в приложении нет сознательно.",
     size=13, color=INK2, line_spacing=1.18)

privacy = [
    (OK, "Видео не записывается", "кадры живут в памяти на время анализа"),
    (OK, "Нет разрешения INTERNET", "приложение физически не может "
     "отправить данные"),
    (OK, "Модель встроена в APK", "ML Kit bundled — скачивание не нужно"),
    (OK, "История только локально", "длительность и счётчики событий, "
     "без кадров"),
]
py = 2.56
for col, head, desc in privacy:
    card(s, 0.65, py, 6.0, 0.94)
    rect(s, 0.86, py + 0.34, 0.12, 0.26, fill=col)
    text(s, 1.10, py + 0.16, 2.75, 0.26, head, size=11, color=INK, bold=True)
    text(s, 3.95, py + 0.16, 2.50, 0.26, desc, size=9, color=INK3,
         align=PP_ALIGN.RIGHT)
    py += 1.02

card(s, 7.15, 1.62, 5.53, 4.90)
text(s, 7.42, 1.82, 5.0, 0.26, "БЕЗОПАСНЫЙ ДОСТУП К КАМЕРЕ", size=9,
     color=ACCENT, bold=True, letter=1.4)
text(s, 7.42, 2.14, 5.0, 0.9,
     "Сервис объявлен как foreground-service с типом camera — Android "
     "показывает его в уведомлении и не убивает, пока экран погашен.",
     size=10.5, color=INK2, line_spacing=1.18)
rect(s, 7.42, 3.12, 4.98, 0.014, fill=STROKE)
text(s, 7.42, 3.28, 5.0, 0.26, "ЧТО МОЖНО ПРОВЕРИТЬ САМОМУ", size=9,
     color=OK, bold=True, letter=1.4)
bullet_block(s, 7.42, 3.60, 5.0, [
    "В AndroidManifest.xml нет android.permission.INTERNET.",
    "В коде нет ни одного сетевого вызова.",
    "Исходники открыты: github.com/Ncuha01-cloud/DriverGuard",
], size=9.5, gap=0.62, bullet_color=OK)
text(s, 7.42, 5.62, 5.0, 0.7,
     "Цена приватности — нет облачной истории и синхронизации между "
     "устройствами. Для одного водителя это разумный обмен.",
     size=9.5, color=INK3, line_spacing=1.14)

# ================================================================== 10. Настройки
s = add_slide()
bg(s)
header(s, 10, "Настройки", "Под конкретного водителя")
title(s, 0.98, "Настройки: чувствительность и сигнал", size=29)

picture_rounded(s, shot("05_settings.png"), 0.65, 1.72, 5.02)
picture_rounded(s, shot("06_settings_scroll.png"), 1.62, 1.72, 5.02)

lx = 3.32
text(s, lx, 1.78, 4.2, 0.26, "ОДИН ПОЛЗУНОК ВМЕСТО ШЕСТИ", size=9, color=ACCENT,
     bold=True, letter=1.3)
bullet_block(s, lx, 2.10, 3.95, [
    "Ползунок чувствительности сразу меняет пороги глаз, кивка и зевоты.",
    "От «терпимо» (1,8 с) до «максимально» (0,7 с) до тревоги.",
], size=9.5, gap=0.56)

text(s, lx, 3.28, 4.2, 0.26, "КАЛИБРОВКА ПОСАДКИ", size=9, color=ACCENT,
     bold=True, letter=1.3)
bullet_block(s, lx, 3.60, 3.95, [
    "5 секунд «смотрите прямо» — берётся медиана наклона головы.",
    "Медиана отбрасывает выбросы морганий и случайные кивки.",
], size=9.5, gap=0.56)

text(s, lx, 4.78, 4.2, 0.26, "ПРОВЕРКА СИГНАЛА", size=9, color=ACCENT,
     bold=True, letter=1.3)
bullet_block(s, lx, 5.10, 3.95, [
    "Кнопка «Проверить сигнал» проигрывает тревогу сразу.",
    "Громкость и вибрация настраиваются отдельно.",
], size=9.5, gap=0.56)

card(s, 7.75, 1.72, 4.93, 3.32)
text(s, 8.00, 1.90, 4.4, 0.26, "ЧТО ПОПАДАЕТ В НАСТРОЙКИ", size=9, color=ACCENT,
     bold=True, letter=1.3)
opts = [
    ("Чувствительность", "6 порогов из одного значения"),
    ("Нейтральный наклон", "результат калибровки"),
    ("Громкость тревоги", "0…100 %"),
    ("Вибрация", "ритмичный узор"),
    ("Отладочный оверлей", "контуры поверх превью"),
    ("История поездок", "последние 60 сессий"),
]
oy = 2.24
for head, desc in opts:
    rect(s, 8.00, oy + 0.07, 0.06, 0.20, fill=ACCENT)
    text(s, 8.20, oy, 2.3, 0.24, head, size=10, color=INK, bold=True)
    text(s, 10.35, oy, 2.10, 0.24, desc, size=9, color=INK3, align=PP_ALIGN.RIGHT)
    oy += 0.44
rect(s, 8.00, 4.86, 4.43, 0.014, fill=STROKE)
text(s, 8.00, 4.88, 0.0, 0.0, "")

card(s, 7.75, 5.20, 4.93, 1.50, fill=RGBColor(0x0F, 0x1B, 0x29))
text(s, 8.00, 5.36, 4.4, 0.26, "ПОЧЕМУ ЭТО ВАЖНО", size=9, color=OK, bold=True,
     letter=1.3)
text(s, 8.00, 5.64, 4.45, 0.94,
     "У каждого своя посадка, свой держатель и своя манера вождения. "
     "Жёсткие заводские пороги дают либо ложные тревоги, либо "
     "пропущенный микро-сон.",
     size=9.5, color=INK2, line_spacing=1.14)

# ================================================================== 11-13. Скриншоты
shots_data = [
    (11, "Приветствие и разрешения", "Первый запуск", [
        shot("01_welcome.png"), shot("02_permission.png"),
    ], [
        ("01", "Приветствие", "Сразу объясняет, зачем камера, и показывает "
         "«100 % на устройстве, 0 МБ трафика» — до системного запроса."),
        ("02", "Разрешение камеры", "Системный диалог Android; без согласия "
         "приложение не запускает камеру вовсе."),
    ]),
    (12, "Наблюдение за поездкой", "Главный экран", [
        shot("04_monitor.png"), shot("07_summary.png"),
    ], [
        ("04", "Экран наблюдения", "HUD поверх превью: рамка лица, якоря глаз, "
         "шкала наклона, кольцо бдительности, шкала усталости и счётчики."),
        ("07", "Итоги поездки", "Длительность, число тревог, кивков и зевков; "
         "сессия сохраняется в локальную историю."),
    ]),
    (13, "Тревога", "Момент, ради которого всё сделано", [
        shot("03_alarm.png"),
    ], [
        ("03", "Экран тревоги", "Красный пульсирующий фон, слово «ПРОСНИСЬ», "
         "причина срабатывания и одна кнопка «Я ПРОСНУЛСЯ»."),
    ]),
]
for num, head, kicker, pics, notes in shots_data:
    s = add_slide()
    bg(s)
    header(s, num, "Интерфейс", kicker)
    title(s, 0.98, head, size=30)

    if len(pics) == 2:
        px1, px2 = 0.72, 1.90
        w1 = picture_rounded(s, pics[0], px1, 1.66, 5.10)
        w2 = picture_rounded(s, pics[1], px1 + w1 + 0.42, 1.66, 5.10)
        block_x = px1 + w1 + 0.42 + w2 + 0.62
        n_x = block_x
        n_w = 12.65 - n_x
        ny = 1.72
        for n, hd, desc in notes:
            card(s, n_x, ny, n_w, 2.24)
            text(s, n_x + 0.22, ny + 0.20, 0.6, 0.24, n, size=10, color=ACCENT,
                 bold=True)
            text(s, n_x + 0.22, ny + 0.50, n_w - 0.44, 0.30, hd, size=12.5,
                 color=INK, bold=True)
            text(s, n_x + 0.22, ny + 0.90, n_w - 0.44, 1.20, desc, size=10,
                 color=INK2, line_spacing=1.16)
            ny += 2.52
    else:
        w1 = picture_rounded(s, pics[0], 0.72, 1.66, 5.10)
        n_x = 0.72 + w1 + 0.62
        n_w = 12.65 - n_x
        ny = 1.72
        for n, hd, desc in notes:
            card(s, n_x, ny, n_w, 2.40)
            text(s, n_x + 0.24, ny + 0.22, 0.7, 0.24, n, size=10, color=DANGER,
                 bold=True)
            text(s, n_x + 0.24, ny + 0.54, n_w - 0.48, 0.32, hd, size=13,
                 color=INK, bold=True)
            text(s, n_x + 0.24, ny + 0.96, n_w - 0.48, 1.30, desc, size=10.5,
                 color=INK2, line_spacing=1.18)
            ny += 2.56
        card(s, n_x, 4.30, n_w, 2.40, fill=RGBColor(0x17, 0x18, 0x22),
             stroke_=RGBColor(0x33, 0x22, 0x2C))
        text(s, n_x + 0.24, 4.52, n_w - 0.48, 0.30, "Что внутри тревоги",
             size=12.5, color=WARN, bold=True)
        bullet_block(s, n_x + 0.24, 4.96, n_w - 0.48, [
            "сирена 880/1320 Гц — два тона внахлёст",
            "громкость поднимается до максимума будильника",
            "экран поверх блокировки, режим «не гасить»",
        ], size=9.5, gap=0.52, bullet_color=WARN)

# ================================================================== 14. Финал
s = add_slide()
bg(s)
rect(s, 0, 0, 13.333, 0.05, fill=ACCENT)
accent_line(s, 0.75, 1.28, 0.30)
text(s, 1.19, 1.16, 8.0, 0.26, "ИТОГ", size=10, color=ACCENT, bold=True,
     letter=2.2)
title(s, 1.52, [[("Установка и ", {}), ("исходный код", {"color": ACCENT})]],
      size=34, w=6.0)
text(s, 0.75, 2.58, 6.3, 0.6,
     "Приложение собирается одной командой, а код, README и скриншоты лежат "
     "в открытом репозитории.", size=12.5, color=INK2, line_spacing=1.2)

card(s, 0.75, 3.24, 6.3, 0.72, fill=RGBColor(0x0F, 0x16, 0x24))
text(s, 0.98, 3.42, 5.9, 0.36,
     [[("$ ", {"color": INK3}),
       ("git clone " + REPO + ".git", {"color": INK})]],
     size=11, font="Consolas")
card(s, 0.75, 4.04, 6.3, 0.72, fill=RGBColor(0x0F, 0x16, 0x24))
text(s, 0.98, 4.22, 5.9, 0.36,
     [[("$ ", {"color": INK3}), ("./gradlew installDebug", {"color": INK})]],
     size=11, font="Consolas")
text(s, 0.75, 4.96, 6.3, 0.9,
     "Нужны Android 8.0+, JDK 17 и Android SDK 34. Подробная инструкция и "
     "описание порогов — в README репозитория.",
     size=10.5, color=INK3, line_spacing=1.16)

# большая ссылка
card(s, 7.35, 1.28, 5.33, 3.48, fill=RGBColor(0x0D, 0x1B, 0x29),
     stroke_=RGBColor(0x22, 0x4A, 0x5E))
text(s, 7.62, 1.50, 4.8, 0.26, "РЕПОЗИТОРИЙ", size=9, color=ACCENT, bold=True,
     letter=1.6)
text(s, 7.62, 1.84, 4.85, 0.34, "github.com/Ncuha01-cloud/DriverGuard",
     size=13, color=INK, bold=True)
text(s, 7.62, 2.28, 4.85, 0.34,
     [[("Открыть на GitHub  →", {"color": ACCENT, "bold": True,
                                 "link": REPO})]], size=12)
text(s, 7.62, 2.94, 4.85, 1.0,
     "Лицензия MIT · Kotlin · Android · ML Kit. Та же ссылка размещена "
     "в README и описании репозитория.", size=10, color=INK2,
     line_spacing=1.16)

# QR-код, если сгенерирован
qr = os.path.join(PROJECT, "docs", "qr_repo.png")
if os.path.exists(qr):
    s.shapes.add_picture(qr, Inches(12.02), Inches(1.62), height=Inches(1.5))

text(s, 7.62, 4.10, 4.85, 0.34, "Гиперссылка на слайде", size=9, color=INK3,
     bold=True, letter=1.2)

rect(s, 0.75, 6.20, 11.9, 0.012, fill=STROKE)
text(s, 0.75, 6.58, 11.9, 0.22,
     "DriverGuard — вспомогательный инструмент, а не замена отдыху "
     "и внимательности.", size=9, color=INK3)

# --- кликабельные ссылки на финальном слайде ---
for shape in s.shapes:
    if not shape.has_text_frame:
        continue
    txt = shape.text_frame.text.strip()
    if txt == "github.com/Ncuha01-cloud/DriverGuard":
        for para in shape.text_frame.paragraphs:
            for r in para.runs:
                r.hyperlink.address = REPO
                r.font.color.rgb = ACCENT

# настоящая строка URL (видимый полный адрес)
text(s, 0.75, 6.30, 6.3, 0.22, REPO, size=8, color=INK3)

prs.save(OUT)
print("saved", OUT, "slides", len(prs.slides))
