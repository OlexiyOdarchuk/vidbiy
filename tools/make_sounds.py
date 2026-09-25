#!/usr/bin/env python3
"""Синтезує вбудовані звуки будильника (без сторонніх семплів — жодних питань з ліцензіями).

    python3 tools/make_sounds.py

Пише MP3 у web/sounds/ та app/src/main/res/raw/. Потрібні numpy і ffmpeg.
"""
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100
ROOT = Path(__file__).resolve().parent.parent
rng = np.random.default_rng(7)


def t_of(dur):
    return np.arange(int(SR * dur)) / SR


def note(freq):
    """Частота ноти за назвою, напр. 'C5', 'F#4'."""
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    name, octave = freq[:-1], int(freq[-1])
    return 440.0 * 2 ** ((names[name] + 12 * (octave - 4)) / 12)


def place(buf, sig, at):
    i = int(at * SR)
    end = min(len(buf), i + len(sig))
    buf[i:end] += sig[: end - i]


def bell(f, dur=1.6, bright=1.0):
    t = t_of(dur)
    partials = [(1, 1.0, 1.0), (2.0, 0.35 * bright, 1.8), (3.0, 0.18 * bright, 2.6), (4.2, 0.08 * bright, 3.5)]
    sig = sum(a * np.sin(2 * np.pi * f * m * t) * np.exp(-t * d * 2.2) for m, a, d in partials)
    attack = np.minimum(1, t / 0.004)
    return sig * attack


def marimba(f, dur=0.9):
    t = t_of(dur)
    sig = np.sin(2 * np.pi * f * t) * np.exp(-t * 6) + 0.25 * np.sin(2 * np.pi * f * 4 * t) * np.exp(-t * 30)
    return sig * np.minimum(1, t / 0.002)


def pluck(f, dur=2.0):
    """Струна за алгоритмом Карплуса — Стронга."""
    n = int(SR / f)
    buf = rng.uniform(-1, 1, n)
    out = np.zeros(int(SR * dur))
    for i in range(len(out)):
        out[i] = buf[i % n]
        buf[i % n] = 0.996 * 0.5 * (buf[i % n] + buf[(i + 1) % n])
    return out


def echo(sig, delay=0.18, decay=0.35, times=3):
    out = np.concatenate([sig, np.zeros(int(SR * delay * times))])
    for k in range(1, times + 1):
        place(out, sig * decay**k, delay * k)
    return out[: len(sig)]


def sunrise():
    """М'які дзвіночки, мажорне арпеджіо вгору."""
    total = 4.8
    buf = np.zeros(int(SR * total))
    seq = ["C5", "E5", "G5", "C6", "E6", "G6", "E6", "C6"]
    for i, n in enumerate(seq):
        place(buf, bell(note(n), 1.8, 0.6) * 0.5, i * 0.28)
    place(buf, bell(note("C5"), 2.2, 0.4) * 0.35, 0)
    return echo(buf, 0.21, 0.3)


def marimba_tune():
    """Бадьора мелодія маримби."""
    total = 3.2
    buf = np.zeros(int(SR * total))
    seq = [("E5", 0), ("G5", 0.2), ("C6", 0.4), ("G5", 0.6), ("E5", 0.8), ("G5", 1.0), ("D6", 1.2),
           ("C6", 1.6), ("E5", 1.8), ("G5", 2.0), ("C6", 2.2), ("E6", 2.4)]
    for n, at in seq:
        place(buf, marimba(note(n)) * 0.6, at)
        place(buf, marimba(note(n) / 2) * 0.25, at)
    return buf


def twin_bells():
    """Механічний будильник з двома дзвониками."""
    total = 2.4
    buf = np.zeros(int(SR * total))
    hits = np.arange(0, 1.6, 1 / 22)
    for k, at in enumerate(hits):
        f = 2350 if k % 2 == 0 else 2780
        t = t_of(0.12)
        sig = (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * f * 2.76 * t) + 0.25 * np.sin(2 * np.pi * f * 5.4 * t))
        place(buf, sig * np.exp(-t * 30) * 0.5, at)
    return buf


def classic():
    """Класичний електронний сигнал: чотири м'які біпи й пауза."""
    total = 2.0
    buf = np.zeros(int(SR * total))
    t = t_of(0.13)
    env = np.minimum(1, np.minimum(t / 0.008, (0.13 - t) / 0.02))
    beep = (np.sin(2 * np.pi * 1250 * t) + 0.3 * np.sin(2 * np.pi * 2500 * t)) * env
    for i in range(4):
        place(buf, beep * 0.8, i * 0.24)
    return buf


def pulse():
    """Низькі хвилі, що наростають — не такі різкі."""
    total = 3.0
    buf = np.zeros(int(SR * total))
    t = t_of(0.5)
    env = np.sin(np.pi * t / 0.5) ** 2
    for i, (f, g) in enumerate([(392, 0.45), (523, 0.6), (392, 0.75), (659, 0.9)]):
        tone = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * 2 * f * t) + 0.1 * np.sin(2 * np.pi * 3 * f * t)
        place(buf, tone * env * g, i * 0.6)
    return buf


def birds():
    """Пташиний щебет."""
    total = 4.0
    buf = np.zeros(int(SR * total))
    at = 0.05
    while at < total - 0.4:
        n = rng.integers(2, 6)
        base = rng.uniform(2800, 4200)
        for j in range(n):
            d = rng.uniform(0.05, 0.1)
            t = t_of(d)
            sweep = base * (1 + 0.35 * np.sin(np.pi * t / d)) * (1 + rng.uniform(-0.1, 0.1))
            phase = 2 * np.pi * np.cumsum(sweep) / SR
            env = np.sin(np.pi * t / d) ** 2
            place(buf, np.sin(phase) * env * 0.5, at)
            at += d + rng.uniform(0.02, 0.05)
        at += rng.uniform(0.25, 0.6)
    return echo(buf, 0.09, 0.2, 2)


def harp():
    """Глісандо арфи вгору."""
    total = 4.2
    buf = np.zeros(int(SR * total))
    seq = ["C4", "E4", "G4", "B4", "C5", "E5", "G5", "B5", "C6", "E6", "G6"]
    for i, n in enumerate(seq):
        place(buf, pluck(note(n), 2.5) * 0.45, i * 0.09)
    for i, n in enumerate(["C5", "G5", "C6"]):
        place(buf, pluck(note(n), 2.2) * 0.4, 1.8 + i * 0.35)
    return buf


SOUNDS = {
    "sunrise": sunrise,
    "marimba": marimba_tune,
    "harp": harp,
    "birds": birds,
    "pulse": pulse,
    "classic": classic,
    "bells": twin_bells,
}


# Рівні тони від компресії хрипнуть, тож їх лише нормалізуємо.
DRIVE = {"classic": 0.0, "pulse": 0.0}


def normalize(sig, drive=1.4):
    sig = sig / (np.max(np.abs(sig)) + 1e-9)
    if drive:
        sig = np.tanh(drive * sig)  # м'яка компресія: тихі хвости гучніші, пікові не ріжуть вухо
    return sig / np.max(np.abs(sig)) * 0.95


def write_mp3(sig, path):
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((sig * 32767).astype(np.int16).tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp.name, "-b:a", "128k", str(path)], check=True)


if __name__ == "__main__":
    web = ROOT / "web" / "sounds"
    raw = ROOT / "app" / "src" / "main" / "res" / "raw"
    web.mkdir(parents=True, exist_ok=True)
    raw.mkdir(parents=True, exist_ok=True)
    for name, make in SOUNDS.items():
        sig = normalize(make(), DRIVE.get(name, 1.4))
        write_mp3(sig, web / f"{name}.mp3")
        write_mp3(sig, raw / f"alarm_{name}.mp3")
        print(f"{name}: {len(sig) / SR:.1f} s")
