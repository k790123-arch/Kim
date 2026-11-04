#!/usr/bin/env python3
"""TV Christian Channel: automatic Korean narrated MP4 shorts (starter edition).
Requires ffmpeg, Pillow, edge-tts and an internet connection for TTS.
"""
import argparse
import asyncio
import datetime as dt
import os
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import edge_tts

ROOT = Path(__file__).parent
OUT = ROOT / "output"
FONT = os.environ.get("KOREAN_FONT", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
VOICE = os.environ.get("TTS_VOICE", "ko-KR-InJoonNeural")
DAYS = ["월", "화", "수", "목", "금", "토", "일"]

# Original narration templates, not Bible quotations or licensed hymn recordings.
QT = [
    ("오늘의 묵상", "오늘도 말씀 앞에 마음을 엽니다. 하나님께서 주시는 지혜를 구하며 하루를 시작합니다."),
    ("말씀을 생각하며", "사랑과 정직, 그리고 이웃을 향한 배려를 오늘의 삶에서 실천할 수 있도록 마음을 살펴봅니다."),
    ("말씀 나눔", "작은 친절 한 번이 누군가에게는 큰 위로가 될 수 있습니다. 오늘 내가 실천할 한 가지를 정해 봅니다."),
    ("기도", "하나님, 오늘도 올바른 길을 선택하게 하시고 사랑으로 사람들을 대하게 도와주세요. 예수님의 이름으로 기도합니다. 아멘."),
]
EXTRA = {
    0: ("성경 이야기", "오늘은 성경의 인물들이 보여 준 믿음과 용기를 생각해 봅니다. 어려운 순간에도 선한 선택을 하는 삶을 묵상해 보세요."),
    1: ("재미있는 성경 이야기", "성경 이야기는 흥미롭지만 사실을 바꾸어서는 안 됩니다. 오늘은 성경을 직접 읽으며 놀라운 장면과 그 의미를 함께 생각해 봅니다."),
    3: ("성경 이야기", "오늘의 성경 이야기에서는 서로를 돕는 마음과 겸손의 가치를 생각해 봅니다. 본문을 읽고 자신의 삶에 적용해 보세요."),
    5: ("따뜻한 세상 이야기", "평범한 하루에도 미소 지을 일이 있습니다. 가까운 사람에게 고마운 마음을 표현하고 작지만 즐거운 순간을 만들어 보세요."),
}

def run(cmd):
    subprocess.run(cmd, check=True)

def wrap(draw, text, font, max_width):
    lines = []
    for para in text.split("\n"):
        cur = ""
        for ch in para:
            if draw.textbbox((0, 0), cur + ch, font=font)[2] > max_width and cur:
                lines.append(cur)
                cur = ch
            else:
                cur += ch
        if cur:
            lines.append(cur)
    return lines

def card(title, body, path):
    im = Image.new("RGB", (1080, 1920), (17, 32, 51))
    d = ImageDraw.Draw(im)
    big = ImageFont.truetype(FONT, 75)
    small = ImageFont.truetype(FONT, 51)
    brand = ImageFont.truetype(FONT, 40)
    d.rounded_rectangle((70, 210, 1010, 1680), radius=45, fill=(29, 52, 73))
    d.text((110, 280), "TV기독교 채널", font=brand, fill=(245, 202, 125))
    y = 510
    for line in wrap(d, title, big, 840):
        d.text((115, y), line, font=big, fill="white")
        y += 105
    y += 90
    for line in wrap(d, body, small, 830):
        d.text((115, y), line, font=small, fill=(226, 235, 240))
        y += 86
        if y > 1570:
            break
    d.text((110, 1730), "오늘도 말씀과 함께", font=brand, fill=(245, 202, 125))
    im.save(path)

async def speech(text, path):
    await edge_tts.Communicate(text, VOICE, rate="-10%").save(str(path))

def render(name, scenes, seconds):
    work = OUT / ("tmp_" + name)
    work.mkdir(parents=True, exist_ok=True)
    segments = []
    each = seconds / len(scenes)
    for i, (title, body) in enumerate(scenes):
        png = work / f"{i:02}.png"
        mp3 = work / f"{i:02}.mp3"
        mp4 = work / f"{i:02}.mp4"
        card(title, body, png)
        asyncio.run(speech(body, mp3))
        run(["ffmpeg", "-y", "-loglevel", "error", "-loop", "1", "-framerate", "24", "-i", str(png),
             "-i", str(mp3), "-filter_complex", f"[1:a]apad,atrim=duration={each:.3f}[a]",
             "-map", "0:v", "-map", "[a]", "-t", f"{each:.3f}",
             "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-pix_fmt", "yuv420p",
             "-r", "24", "-c:a", "aac", "-ar", "44100", "-movflags", "+faststart", str(mp4)])
        segments.append(mp4)
    concat = work / "concat.txt"
    concat.write_text("".join("file '" + p.name + "'\n" for p in segments), encoding="utf-8")
    target = OUT / (name + ".mp4")
    run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(concat),
         "-c", "copy", "-movflags", "+faststart", str(target)])
    print("CREATED", target)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--demo", action="store_true", help="Generate one 20-second MP4")
    args = p.parse_args()
    OUT.mkdir(exist_ok=True)
    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))
    weekday = now.weekday()
    stamp = now.strftime("%Y%m%d")
    if args.demo:
        render("demo_" + stamp, [("MP4 자동 제작 시험", "안녕하세요. TV기독교 채널 자동 영상 제작 시험입니다.")], 20)
        return
    qt_seconds = 120 if weekday >= 5 else 90
    render(f"QT_{stamp}", QT, qt_seconds)
    if weekday in EXTRA:
        title, narration = EXTRA[weekday]
        render(f"story_{stamp}_150s", [(title, narration), ("오늘의 적용", "오늘의 이야기를 듣고 내가 할 수 있는 좋은 일을 하나 실천해 봅시다.")], 150)
        render(f"story_{stamp}_20s", [(title + " 요약", narration)], 20)

if __name__ == "__main__":
    main()
