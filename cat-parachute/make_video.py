#!/usr/bin/env python3
"""Кот-парашют: взлёт с кровати в небо, появление коробки, покачивающийся спуск
и мягкая посадка обратно на кровать. Видео и звук генерируются целиком
из трёх исходных картинок (вырезанный кот с альфой, фон без кота, оригинал).

Запуск:  python3 make_video.py            -> cat_parachute.mp4 рядом со скриптом
Нужны:   Pillow, numpy, ffmpeg (libx264 + aac).
"""
import math
import os
import random
import subprocess
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "assets")
OUT = os.path.join(HERE, "cat_parachute.mp4")

W, H = 768, 1024
FPS = 30
DURATION = 14.5
SR = 44100

# --- таймлайн (секунды) ----------------------------------------------------
T_ANT0 = 0.9       # кот приседает перед прыжком
T_LAUNCH = 1.3     # старт
T_APEX = 3.4       # верхняя точка
T_POP = 3.55       # появляется коробка
T_DESC0 = 3.9      # начало спуска
T_LAND = 12.4      # коробка касается кровати
T_IMPACT = T_LAND  # кот и коробка касаются кровати одновременно
APEX = 3200.0      # высота подъёма в пикселях мира

BOX_W, BOX_H = 280, 160
BOX_TOP_REST = 850     # верх коробки прямо под кончиками лап (мировая y)
BOX_CX_REST = 447      # центр коробки по x — под лапами
GROUND_Y = BOX_TOP_REST + BOX_H  # низ коробки в покое
FOLLOW_Y = 380     # камера держит центр кота на этой высоте экрана

random.seed(7)
np.random.seed(7)


# --- вспомогательные --------------------------------------------------------
def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)


def rot(dx, dy, deg):
    """Визуальный поворот против часовой стрелки (как Image.rotate) в экранных координатах."""
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return dx * c + dy * s, -dx * s + dy * c


# --- спрайты ----------------------------------------------------------------
cat_full = Image.open(os.path.join(ASSETS, "cat_alpha.png")).convert("RGBA")
bbox = cat_full.getbbox()
PAD = 4
crop_box = (bbox[0] - PAD, bbox[1] - PAD, bbox[2] + PAD, bbox[3] + PAD)
CAT = cat_full.crop(crop_box)
CAT_W, CAT_H = CAT.size
CAT_REST_CX = crop_box[0] + CAT_W / 2.0
CAT_REST_CY = crop_box[1] + CAT_H / 2.0
CAT_BOTTOM_REST = bbox[3]
# смещение центра коробки от центра кота: кот держит её лапами
BOX_OFF = (BOX_CX_REST - CAT_REST_CX, BOX_TOP_REST + BOX_H / 2.0 - CAT_REST_CY)

bed = Image.open(os.path.join(ASSETS, "bed_empty.jpg")).convert("RGB").resize((W, H), Image.LANCZOS)


def make_box():
    img = Image.new("RGBA", (BOX_W, BOX_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    front = (198, 156, 98)
    dark = (160, 120, 70)
    edge = (110, 80, 45)
    d.rectangle([0, 0, BOX_W - 1, BOX_H - 1], fill=front, outline=edge, width=3)
    # верхние створки
    d.polygon([(2, 2), (BOX_W - 3, 2), (BOX_W - 30, 28), (30, 28)], fill=dark, outline=edge)
    d.line([(BOX_W // 2, 2), (BOX_W // 2, 28)], fill=edge, width=2)
    # скотч
    d.rectangle([BOX_W // 2 - 13, 2, BOX_W // 2 + 13, BOX_H - 3], fill=(226, 204, 160))
    # стрелки "верх" и надпись
    for x in (46, BOX_W - 46):
        d.polygon([(x, 54), (x - 15, 76), (x + 15, 76)], fill=edge)
        d.rectangle([x - 6, 76, x + 6, 102], fill=edge)
    try:
        font = ImageFont.load_default(size=34)
    except TypeError:
        font = ImageFont.load_default()
    d.text((BOX_W // 2, int(BOX_H * 0.72)), "КОТ", fill=edge, font=font, anchor="mm")
    return img


BOX = make_box()


def make_cloud(wd, ht, seed, alpha=235):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (wd, ht), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    n = rnd.randint(5, 9)
    for _ in range(n):
        ew = rnd.randint(int(wd * 0.3), int(wd * 0.6))
        eh = rnd.randint(int(ht * 0.35), int(ht * 0.7))
        x = rnd.randint(0, wd - ew)
        y = rnd.randint(int(ht * 0.15), ht - eh)
        d.ellipse([x, y, x + ew, y + eh], fill=(255, 255, 255, alpha))
    # плоское основание
    d.rectangle([int(wd * 0.1), int(ht * 0.6), int(wd * 0.9), int(ht * 0.8)], fill=(255, 255, 255, alpha))
    return img.filter(ImageFilter.GaussianBlur(5))


def make_sky_strip():
    """Фон мира: небо сверху (SKY_H px), кровать снизу (H px). Мировая y = строка - SKY_H."""
    sky_h = int(APEX + 700)
    strip = Image.new("RGBA", (W, sky_h + H))
    top = np.array([58, 118, 212], dtype=float)
    horizon = np.array([178, 212, 242], dtype=float)
    rows = np.linspace(0, 1, sky_h)[:, None] ** 0.8
    grad = (top * (1 - rows) + horizon * rows).astype(np.uint8)
    grad = np.repeat(grad[:, None, :], W, axis=1)
    strip.paste(Image.fromarray(grad, "RGB").convert("RGBA"), (0, 0))
    strip.paste(bed.convert("RGBA"), (0, sky_h))
    # солнце
    sun = Image.new("RGBA", (W, sky_h), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sun)
    sd.ellipse([540, 380, 740, 580], fill=(255, 245, 200, 140))
    sun = sun.filter(ImageFilter.GaussianBlur(60))
    sd = ImageDraw.Draw(sun)
    sd.ellipse([590, 430, 690, 530], fill=(255, 250, 225, 255))
    sun = sun.filter(ImageFilter.GaussianBlur(3))
    strip.alpha_composite(sun, (0, 0))
    # облака
    for i in range(30):
        wd = random.randint(220, 520)
        ht = random.randint(90, 200)
        cl = make_cloud(wd, ht, 100 + i, alpha=random.randint(190, 240))
        x = random.randint(-120, W - wd + 120)
        y = random.randint(250, sky_h - 150)
        strip.alpha_composite(cl, (x, y))
    return strip.convert("RGB"), sky_h


STRIP, SKY_H = make_sky_strip()

NEAR_CLOUDS = []
for i in range(8):
    wd = random.randint(320, 560)
    ht = random.randint(120, 220)
    spr = make_cloud(wd, ht, 500 + i, alpha=245)
    wx = random.randint(-200, W - wd + 200)
    wy = random.randint(int(-1.35 * (APEX + 200) - 400), -300)
    NEAR_CLOUDS.append((spr, wx, wy))
NEAR_PARALLAX = 1.35

# мягкий переход "спинка дивана -> небо", когда камера уходит вверх
FADE_H = 380
HORIZON_FADE = Image.new("RGBA", (W, FADE_H), (178, 212, 242, 0))
_u = np.linspace(0, 1, FADE_H)
_a = np.repeat((255 * (1 - _u * _u * (3 - 2 * _u))).astype(np.uint8)[:, None], W, axis=1)
HORIZON_FADE.putalpha(Image.fromarray(_a))


# --- движение ---------------------------------------------------------------
def _descent_curve():
    u = np.linspace(0, 1, 4000)
    v = np.clip((u / 0.12), 0, 1)
    v = v * v * (3 - 2 * v) * (1 - 0.45 * u)   # разгон, потом плавно медленнее
    s = np.cumsum(v)
    s -= s[0]
    s /= s[-1]
    return u, s


_DU, _DS = _descent_curve()


def rel_y(t):
    """Смещение центра кота по y относительно места на кровати (отрицательное = вверх)."""
    if t < T_LAUNCH:
        return 0.0
    if t < T_APEX:
        u = (t - T_LAUNCH) / (T_APEX - T_LAUNCH)
        return -APEX * (1 - (1 - u) ** 2.5)
    if t < T_DESC0:
        u = (t - T_APEX) / (T_DESC0 - T_APEX)
        return -APEX - 30 * math.sin(math.pi * u)
    if t < T_LAND:
        u = (t - T_DESC0) / (T_LAND - T_DESC0)
        return -APEX + APEX * float(np.interp(u, _DU, _DS))
    return 0.0


def vel_y(t, dt=1.0 / 120):
    return (rel_y(t + dt) - rel_y(t - dt)) / (2 * dt)


SWAY_PERIOD = 2.4


def sway_amp(t):
    if t < T_DESC0 or t > T_LAND:
        return 0.0
    tt = t - T_DESC0
    return smoothstep(0, 1.3, tt) * (1 - smoothstep(T_LAND - 1.7, T_LAND - 0.15, t))


def cat_angle(t):
    a = 0.0
    if T_LAUNCH <= t < T_DESC0:
        tt = t - T_LAUNCH
        a += 14 * math.sin(2 * math.pi * 0.8 * tt) * math.exp(-0.9 * tt) * (1 - smoothstep(T_APEX, T_DESC0, t))
    if T_DESC0 <= t <= T_LAND:
        tt = t - T_DESC0
        a += 13 * sway_amp(t) * math.sin(2 * math.pi * tt / SWAY_PERIOD)
    return a


def rel_x(t):
    x = 0.0
    if T_LAUNCH <= t < T_DESC0:
        tt = t - T_LAUNCH
        x += 18 * math.sin(2 * math.pi * 0.8 * tt + 1.2) * math.exp(-0.9 * tt) * (1 - smoothstep(T_APEX, T_DESC0, t))
    if T_DESC0 <= t <= T_LAND:
        tt = t - T_DESC0
        k = sway_amp(t)
        # справа в кадре больше места, поэтому дрейф смещён вправо
        x += k * (30 * math.sin(2 * math.pi * tt / SWAY_PERIOD - 0.7) + 22 * math.sin(2 * math.pi * tt / 7.3) + 35)
    return x


def box_angle(t):
    # груз чуть запаздывает и качается сильнее
    if t > T_LAND:
        return 0.0
    return 1.2 * cat_angle(max(T_DESC0, t - 0.12)) if t >= T_DESC0 else 0.0


def cat_squash(t):
    """(sx, sy) с якорем на нижней точке кота."""
    sx = sy = 1.0
    if T_ANT0 <= t < T_LAUNCH:
        u = (t - T_ANT0) / (T_LAUNCH - T_ANT0)
        k = math.sin(math.pi * u) ** 0.7
        sy -= 0.10 * k
        sx += 0.06 * k
    if T_LAUNCH <= t < T_LAUNCH + 0.25:
        u = (t - T_LAUNCH) / 0.25
        k = math.sin(math.pi * u)
        sy += 0.12 * k    # вытягивается на старте
        sx -= 0.07 * k
    if T_IMPACT <= t < T_IMPACT + 0.4:
        u = (t - T_IMPACT) / 0.4
        k = math.sin(math.pi * u) * (1 - 0.5 * u)
        sy -= 0.11 * k
        sx += 0.07 * k
    return sx, sy


def box_scale(t):
    if t < T_POP:
        return 0.0
    u = (t - T_POP) / 0.35
    if u >= 1:
        return 1.0
    if u < 0.6:
        v = u / 0.6
        return 1.22 * (1 - (1 - v) ** 3)
    return 1.22 - 0.22 * (u - 0.6) / 0.4


def box_squash(t):
    if T_LAND <= t < T_LAND + 0.3:
        u = (t - T_LAND) / 0.3
        k = math.sin(math.pi * u)
        return 1 + 0.10 * k, 1 - 0.14 * k
    return 1.0, 1.0


# --- отрисовка кадра --------------------------------------------------------
def scaled_alpha(img, k):
    if k >= 0.999:
        return img
    out = img.copy()
    out.putalpha(out.getchannel("A").point(lambda a: int(a * k)))
    return out


def draw_puff(frame, cx, cy, u, size, cam_y, color=(236, 230, 220)):
    """Облачко пыли, u в [0,1]."""
    if u < 0 or u >= 1:
        return
    r = size * (0.25 + 0.75 * (1 - (1 - u) ** 2))
    pad = int(r * 1.4 + 40)
    layer = Image.new("RGBA", (pad * 2, pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    rnd = random.Random(int(size))
    alpha = int(170 * (1 - u) ** 1.3)
    for i in range(10):
        ang = rnd.uniform(0, 2 * math.pi)
        rr = r * rnd.uniform(0.5, 1.0)
        ew = size * rnd.uniform(0.25, 0.5) * (0.6 + u)
        eh = ew * rnd.uniform(0.5, 0.8)
        x = pad + rr * math.cos(ang) - ew / 2
        y = pad + rr * math.sin(ang) * 0.35 - eh / 2
        d.ellipse([x, y, x + ew, y + eh], fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(7))
    frame.alpha_composite(layer, (int(cx - pad), int(cy - cam_y - pad)))


def bezier(p0, p1, p2, n=14):
    pts = []
    for i in range(n + 1):
        s = i / n
        x = (1 - s) ** 2 * p0[0] + 2 * (1 - s) * s * p1[0] + s * s * p2[0]
        y = (1 - s) ** 2 * p0[1] + 2 * (1 - s) * s * p1[1] + s * s * p2[1]
        pts.append((x, y))
    return pts


def render_frame(t):
    ry = rel_y(t)
    rx = rel_x(t)
    cx = CAT_REST_CX + rx
    cy = CAT_REST_CY + ry
    cam_y = min(0.0, cy - FOLLOW_Y)
    vy = vel_y(t)
    ang = cat_angle(t)

    # фон
    top_row = int(round(cam_y + SKY_H))
    frame = STRIP.crop((0, top_row, W, top_row + H)).convert("RGBA")

    f = smoothstep(0, 70, -cam_y)
    if f > 0:
        frame.alpha_composite(scaled_alpha(HORIZON_FADE, f), (0, int(round(-cam_y))))

    # ближние облака (параллакс)
    for spr, wx, wy in NEAR_CLOUDS:
        sy = wy - NEAR_PARALLAX * cam_y
        if -spr.height < sy < H:
            frame.alpha_composite(spr, (wx, int(sy)))

    # пыль при старте и посадке
    draw_puff(frame, CAT_REST_CX, CAT_BOTTOM_REST - 10, (t - T_LAUNCH) / 0.9, 260, cam_y)
    draw_puff(frame, BOX_CX_REST, GROUND_Y - 6, (t - T_LAND) / 0.7, 230, cam_y)
    draw_puff(frame, CAT_REST_CX - 60, CAT_BOTTOM_REST - 60, (t - T_IMPACT - 0.05) / 0.6, 150, cam_y)

    # линии скорости при быстром подъёме
    speed = abs(vy)
    if speed > 900:
        k = clamp((speed - 900) / 2600)
        od = ImageDraw.Draw(frame, "RGBA")
        rnd = random.Random(int(t * FPS))
        for _ in range(int(6 + 10 * k)):
            x = cx + rnd.uniform(-CAT_W * 0.45, CAT_W * 0.45)
            y0 = cy - cam_y + rnd.uniform(-CAT_H * 0.3, CAT_H * 0.5)
            ln = (60 + 200 * k) * rnd.uniform(0.5, 1.0)
            sign = 1 if vy < 0 else -1
            od.line([(x, y0), (x, y0 + sign * ln)], fill=(255, 255, 255, int(70 + 90 * k)), width=3)

    # спрайт кота
    sx, sy_ = cat_squash(t)
    sprite = CAT
    if abs(sx - 1) > 1e-3 or abs(sy_ - 1) > 1e-3:
        sprite = CAT.resize((max(1, int(CAT_W * sx)), max(1, int(CAT_H * sy_))), Image.BICUBIC)
    bottom = cy + CAT_H / 2.0
    draw_cy = bottom - sprite.height / 2.0   # якорь снизу
    if abs(ang) > 1e-3:
        sprite = sprite.rotate(ang, resample=Image.BICUBIC, expand=True)
    sw, sh = sprite.size
    px, py = cx - sw / 2.0, draw_cy - sh / 2.0 - cam_y

    # коробка: кот держит её лапами, она поворачивается вместе с ним (рисуется под котом)
    bs = box_scale(t)
    if bs > 0:
        bang = ang + (box_angle(t) - ang) * 0.25   # чуть запаздывает за котом
        # при появлении коробка "выдвигается" из-под лап
        offx, offy = BOX_OFF[0], BOX_OFF[1] - (1 - min(1.0, bs)) * BOX_H * 0.5
        dx, dy = rot(offx, offy, ang)
        bx, by = cx + dx, cy + dy
        bsx, bsy = box_squash(t)
        if bsy < 1:   # при посадке сплющивается с опорой на низ
            by += BOX_H * (1 - bsy) / 2.0
        bw, bh = max(1, int(BOX_W * bs * bsx)), max(1, int(BOX_H * bs * bsy))
        box_img = BOX.resize((bw, bh), Image.BICUBIC)
        if abs(bang) > 1e-3:
            box_img = box_img.rotate(bang, resample=Image.BICUBIC, expand=True)
        frame.alpha_composite(box_img, (int(bx - box_img.width / 2.0), int(by - box_img.height / 2.0 - cam_y)))

    # размытие движения (шлейф) при большой скорости
    if speed > 900:
        n = 5
        trail = (vy / FPS) * 0.9
        for k in range(n, 0, -1):
            off = -trail * k / n
            frame.alpha_composite(scaled_alpha(sprite, 0.30 * (1 - k / (n + 1))), (int(px), int(py + off)))
    frame.alpha_composite(sprite, (int(round(px)), int(round(py))))
    return frame.convert("RGB")


# --- звук -------------------------------------------------------------------
def onepole_lp(x, fc):
    fc = np.broadcast_to(np.asarray(fc, dtype=float), x.shape)
    a = 1.0 - np.exp(-2.0 * np.pi * fc / SR)
    y = np.empty_like(x)
    acc = 0.0
    xl, al = x.tolist(), a.tolist()
    for i in range(len(xl)):
        acc += al[i] * (xl[i] - acc)
        y[i] = acc
    return y


def env_ad(n, attack, decay, curve=2.0):
    t = np.arange(n) / SR
    e = np.where(t < attack, t / max(attack, 1e-6), np.exp(-(t - attack) / decay * curve))
    return e


def place(mix, sig, t0, gain=1.0):
    i0 = int(t0 * SR)
    i1 = min(len(mix), i0 + len(sig))
    if i1 > i0:
        mix[i0:i1] += sig[: i1 - i0] * gain


def make_audio():
    n = int(DURATION * SR)
    mix = np.zeros(n)
    tt = np.arange(n) / SR

    # 1. свист-вжух на старте
    L = int(1.8 * SR)
    noise = np.random.uniform(-1, 1, L)
    u = np.arange(L) / L
    sweep = onepole_lp(noise, 250 + 5000 * (u ** 0.6))
    sweep = sweep - onepole_lp(sweep, 150 + 1500 * u)      # отрезаем низ -> "ш-ш"
    sweep *= env_ad(L, 0.06, 0.55, 2.2)
    place(mix, sweep, T_LAUNCH - 0.02, 1.4)
    # мультяшный свисток вверх
    Ls = int(1.0 * SR)
    ts = np.arange(Ls) / SR
    f = 480 * (1500 / 480) ** (ts / 1.0)
    f *= 1 + 0.025 * np.sin(2 * np.pi * 6 * ts)
    ph = 2 * np.pi * np.cumsum(f) / SR
    whistle = (np.sin(ph) + 0.3 * np.sin(2 * ph)) * env_ad(Ls, 0.04, 0.5, 1.5)
    place(mix, whistle, T_LAUNCH + 0.05, 0.11)

    # 2. ветер на протяжении полёта: громкость зависит от скорости
    vel = np.array([abs(vel_y(x)) for x in np.arange(0, DURATION + 0.02, 0.01)])
    vcurve = np.interp(tt, np.arange(len(vel)) * 0.01, vel)
    wind_amp = 0.04 + 0.42 * np.clip(vcurve / 2200, 0, 1) ** 0.8
    wind_amp *= 1 + 0.25 * np.sin(2 * np.pi * 0.37 * tt) * np.sin(2 * np.pi * 0.11 * tt + 1)
    gate = np.interp(tt, [0, T_LAUNCH - 0.05, T_LAUNCH + 0.3, T_IMPACT - 0.2, T_IMPACT + 0.4, DURATION],
                     [0, 0, 1, 1, 0, 0])
    wind = onepole_lp(np.random.uniform(-1, 1, n), 350 + 900 * np.clip(vcurve / 2200, 0, 1))
    wind = wind - onepole_lp(wind, 60)
    mix += wind * wind_amp * gate * 0.9

    # 3. "пок" — появление коробки
    Lp = int(0.22 * SR)
    tp = np.arange(Lp) / SR
    fp = 1100 * np.exp(-tp * 9) + 260
    pop = np.sin(2 * np.pi * np.cumsum(fp) / SR) * env_ad(Lp, 0.004, 0.07, 2.5)
    pop += onepole_lp(np.random.uniform(-1, 1, Lp), 3000) * env_ad(Lp, 0.002, 0.03, 3)
    place(mix, pop, T_POP, 0.55)

    # 4. скрип строп в крайних точках качания
    half = SWAY_PERIOD / 2
    tcr = T_DESC0 + SWAY_PERIOD / 4
    k = 0
    while tcr < T_LAND - 1.2:
        Lc = int(0.32 * SR)
        tc = np.arange(Lc) / SR
        creak = onepole_lp(np.random.uniform(-1, 1, Lc), 900)
        creak -= onepole_lp(creak, 300)
        creak *= (1 + 0.8 * np.sign(np.sin(2 * np.pi * (34 + 10 * (k % 3)) * tc))) * 0.5
        creak *= env_ad(Lc, 0.05, 0.12, 2)
        amp = 0.16 * sway_amp(tcr)
        place(mix, creak, tcr + random.uniform(-0.05, 0.05), amp)
        tcr += half
        k += 1

    # 5. удары: коробка, потом кот
    def thud(dur, f0, f1, noise_fc, noise_gain):
        Lt = int(dur * SR)
        tq = np.arange(Lt) / SR
        fq = f1 + (f0 - f1) * np.exp(-tq * 18)
        s = np.sin(2 * np.pi * np.cumsum(fq) / SR) * env_ad(Lt, 0.004, dur * 0.35, 2.5)
        s += onepole_lp(np.random.uniform(-1, 1, Lt), noise_fc) * env_ad(Lt, 0.002, 0.05, 3) * noise_gain
        return s

    place(mix, thud(0.45, 130, 55, 500, 0.8), T_LAND, 0.9)
    place(mix, thud(0.4, 90, 45, 260, 1.4), T_IMPACT + 0.06, 0.75)
    # лёгкое "фр-р" от покрывала
    Lf = int(0.3 * SR)
    fl = onepole_lp(np.random.uniform(-1, 1, Lf), 1800) * env_ad(Lf, 0.02, 0.09, 2)
    place(mix, fl, T_IMPACT + 0.02, 0.25)

    # нормализация и запись
    mix = np.tanh(mix * 1.1)
    mix *= 0.92 / max(1e-6, np.abs(mix).max())
    pcm = (mix * 32767).astype(np.int16)
    path = os.path.join(HERE, "_audio.wav")
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        wf.writeframes(pcm.tobytes())
    return path


# --- сборка -----------------------------------------------------------------
def main():
    audio = make_audio()
    nframes = int(DURATION * FPS)
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
        "-i", audio,
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "19", "-preset", "medium",
        "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart",
        OUT,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    previews = {}
    for i in range(nframes):
        t = i / FPS
        fr = render_frame(t)
        proc.stdin.write(fr.tobytes())
        if i % 60 == 0:
            previews[i] = fr
        if i % 30 == 0:
            print(f"frame {i}/{nframes} t={t:.2f}s", flush=True)
    proc.stdin.close()
    proc.wait()
    os.remove(audio)
    if proc.returncode != 0:
        raise SystemExit(f"ffmpeg failed with code {proc.returncode}")
    prev_dir = os.environ.get("CAT_PREVIEW_DIR")
    if prev_dir:
        os.makedirs(prev_dir, exist_ok=True)
        for i, fr in previews.items():
            fr.save(os.path.join(prev_dir, f"f{i:04d}.jpg"), quality=85)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
