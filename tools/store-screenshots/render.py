"""Frames the raw screen captures in raw/ for the store and the README: each one goes into a
phone frame on a dark background, under a headline. The results are written to
metadata/en-US/images/phoneScreenshots/<nn>.jpg, which F-Droid shows and the README links to.
The numbers are zero-padded because F-Droid sorts the names as text: 10.jpg would come before 2.jpg.

The layout is plain HTML/CSS, rendered by headless Chrome, so Google Chrome must be installed.

usage: python3 tools/store-screenshots/render.py [n ...]   (no numbers: all of them)
"""
import io
import pathlib
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parents[1] / "metadata/en-US/images/phoneScreenshots"

# The app's languages, in their own names; the AI's answers can be in any of them.
LANGUAGES = ["English", "Deutsch", "Español", "Français", "Italiano", "Nederlands", "Polski",
             "Português", "Magyar", "Türkçe", "Русский", "Українська", "हिन्दी", "Bahasa Indonesia",
             "Tiếng Việt", "日本語", "한국어", "简体中文"]

AI = "On-device AI · nothing leaves your phone"

# n: (headline, subline, extras). "*words*" in the headline get the accent gradient.
# Extras: badge = a lock badge above the headline; languages = chips listing LANGUAGES.
SHOTS = {
    1: ("Calculator, *refined*", "Clean, fast and private. No ads, no tracking.", {}),
    2: ("Scientific, *simply*", "Brackets, roots, powers, e, ln, log, sin, cos and tan.", {}),
    3: ("History, *kept*", "Every calculation, one tap away.", {}),
    4: ("Made for *every screen*", "Turn it sideways and your history sits right beside the keys.", {}),
    5: ("Solve it *with AI*", "Snap a photo, write it by hand, or type it.", {"badge": AI}),
    6: ("Write it *by hand*", "Draw the problem. The AI reads it, right on your phone.", {"badge": AI}),
    7: ("See *every step*", "Not just the answer. How to get there.", {"badge": AI}),
    8: ("Ask *why*", "Word problems too, explained your way.", {"badge": AI}),
    9: ("Answers, *saved*", "Your AI history never leaves your phone.", {"badge": AI}),
    10: ("In *your language*", f"{len(LANGUAGES)} languages, translated on your phone.",
         {"badge": AI, "languages": True}),
    11: ("Private *by design*", "Open models, fully offline. No account, no cloud, no tracking.", {"badge": AI}),
}

LOCK = ('<svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" '
        'stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">'
        '<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>')

CHROME_CANDIDATES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
]

STYLE = """
  html, body { margin:0; overflow:hidden; }
  body { font-family: -apple-system, "SF Pro Display", "Inter", "Helvetica Neue", sans-serif;
         -webkit-font-smoothing: antialiased;
         background:
           radial-gradient(1100px 900px at 50% 62%, rgba(56,189,248,.28), transparent 70%),
           radial-gradient(900px 700px at 85% 8%, rgba(129,140,248,.22), transparent 70%),
           linear-gradient(180deg, #0b1020 0%, #070910 100%); }
  h1 { margin:0; font-weight:800; letter-spacing:-4px; color:#f8fafc; }
  p  { font-weight:500; letter-spacing:-.5px; color:#94a3b8; }
  .accent { background: linear-gradient(90deg, #67e8f9, #a5b4fc);
            -webkit-background-clip: text; color: transparent; }
  .phone { position:absolute; background:#0a0b0e;
           box-shadow: 0 0 0 2px #2a3242, 0 80px 160px rgba(0,0,0,.65), 0 0 240px rgba(56,189,248,.18); }
  .phone::before { content:""; position:absolute; inset:8px; border-radius:inherit;
           box-shadow: inset 0 0 0 2px rgba(255,255,255,.07); pointer-events:none; }
  .screen { display:block; width:100%; }
  .cam { position:absolute; border-radius:50%; background:#050608; box-shadow: inset 0 0 0 5px #11141a; }
  .badge { display:inline-flex; align-items:center; gap:18px; margin-bottom:46px;
           padding:18px 38px 18px 30px; border-radius:999px; font-size:40px; font-weight:600;
           color:#a5f3fc; background:rgba(103,232,249,.10); box-shadow: inset 0 0 0 2px rgba(103,232,249,.35); }
  .langs { display:flex; flex-wrap:wrap; justify-content:center; gap:18px 18px; margin-top:52px; }
  .lang { padding:14px 30px; border-radius:999px; font-size:40px; font-weight:600; color:#e2e8f0;
          background:rgba(148,163,184,.12); box-shadow: inset 0 0 0 2px rgba(148,163,184,.28); }
"""

# A portrait capture: headline on top, the phone below it.
PORTRAIT = """
  html, body { width:1440px; height:2960px; }
  .head { position:absolute; top:190px; left:0; right:0; text-align:center; padding:0 120px; }
  h1 { font-size:132px; line-height:1.04; }
  p  { margin:44px 0 0; font-size:54px; line-height:1.3; }
  .phone { left:50%; top:600px; width:1040px; transform:translateX(-50%); border-radius:132px; padding:30px; }
  .screen { border-radius:104px; }
  .cam { top:58px; left:50%; width:34px; height:34px; margin-left:-17px; }
"""

# A landscape capture: a wide canvas, the headline on the left and the phone on its side.
LANDSCAPE = """
  html, body { width:2960px; height:1440px; }
  .head { position:absolute; left:150px; top:50%; width:820px; transform:translateY(-50%); }
  h1 { font-size:118px; line-height:1.04; }
  p  { margin:40px 0 0; font-size:50px; line-height:1.3; }
  .phone { right:130px; top:50%; width:1760px; transform:translateY(-50%); border-radius:118px; padding:28px; }
  .screen { border-radius:92px; }
  .cam { left:52px; top:50%; width:30px; height:30px; margin-top:-15px; }
"""


def chrome():
    for c in CHROME_CANDIDATES:
        if pathlib.Path(c).exists() or shutil.which(c):
            return c
    sys.exit("Google Chrome (or Chromium) is needed to render the screenshots.")


def render(n, browser):
    headline, subline, extras = SHOTS[n]
    parts = headline.split("*")
    headline = "".join(f'<span class="accent">{t}</span>' if i % 2 else t for i, t in enumerate(parts))
    raw = HERE / "raw" / f"{n}.png"
    width, height = Image.open(raw).size
    layout, size = (LANDSCAPE, (2960, 1440)) if width > height else (PORTRAIT, (1440, 2960))
    badge = f'<div class="badge">{LOCK}{extras["badge"]}</div>' if "badge" in extras else ""
    langs = ""
    if extras.get("languages"):
        langs = '<div class="langs">' + "".join(f'<span class="lang">{l}</span>' for l in LANGUAGES) + "</div>"
    # Push the phone down (and shrink it) to make room for the badge and the language chips.
    if layout is PORTRAIT and (badge or langs):
        top, phone_width = (1150, 790) if langs else (690, 1000)
        layout += (f".head {{ top:130px; }} .phone {{ top:{top}px; width:{phone_width}px; "
                   f"border-radius:{phone_width * 0.127:.0f}px; padding:{phone_width * 0.029:.0f}px; }}"
                   f" .screen {{ border-radius:{phone_width * 0.1:.0f}px; }}")
    html = (f'<!doctype html><html><head><meta charset="utf-8"><style>{STYLE}{layout}</style></head><body>'
            f'<div class="head">{badge}<h1>{headline}</h1><p>{subline}</p>{langs}</div>'
            f'<div class="phone"><img class="screen" src="{raw.as_uri()}"><div class="cam"></div></div>'
            '</body></html>')
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp, "page.html")
        page.write_text(html, encoding="utf-8")
        png = pathlib.Path(tmp, "out.png")
        subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1", f"--window-size={size[0]},{size[1]}",
                        f"--user-data-dir={tmp}/profile", "--allow-file-access-from-files",
                        f"--screenshot={png}", page.as_uri()],
                       check=True, capture_output=True)
        # JPEG at this quality looks the same as PNG here, at about a quarter of the size.
        Image.open(io.BytesIO(png.read_bytes())).convert("RGB").save(
            OUT / f"{n:02d}.jpg", quality=90, subsampling=0, optimize=True, progressive=True)
    print(f"{n:02d}.jpg")


def main():
    browser = chrome()
    for n in [int(a) for a in sys.argv[1:]] or sorted(SHOTS):
        render(n, browser)


if __name__ == "__main__":
    main()
