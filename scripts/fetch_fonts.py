#!/usr/bin/env python3
"""Downloads the variable fonts used by the app into app/src/main/res/font.

Text fonts are subset to the characters the French UI needs. The icon font only
contains the Material Symbols listed in Symbols.kt, so re-run this script after
adding an icon there.  Requires: pip install fonttools brotli
"""
import io
import pathlib
import re
import sys
import urllib.parse
import urllib.request

from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app/src/main/res/font"
SYMBOLS = ROOT / "app/src/main/java/com/allnetworktools/ui/theme/Symbols.kt"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

CHARSET = "".join(chr(c) for c in range(0x20, 0x7F))
CHARSET += "".join(chr(c) for c in range(0xA0, 0x180))
CHARSET += "   ‐‑–—‘’“”„•…‰‹›"
CHARSET += "←↑→↓↔↕⇄−±×≈≠≤≥∞Ω€™★"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def css_font_url(query: str) -> str:
    css = fetch("https://fonts.googleapis.com/css2?" + query).decode()
    urls = re.findall(r"url\((https://[^)]+)\)", css)
    if len(urls) != 1:
        sys.exit(f"Expected exactly one font file for {query!r}, got {len(urls)}")
    return urls[0]


def save_as_ttf(data: bytes, name: str) -> None:
    font = TTFont(io.BytesIO(data))
    font.flavor = None
    out = FONT_DIR / name
    font.save(out)
    axes = [a.axisTag for a in font["fvar"].axes] if "fvar" in font else []
    print(f"{out.relative_to(ROOT)}  {out.stat().st_size // 1024} KB  axes={axes}")


def text_font(family: str, axes: str, name: str) -> None:
    query = f"family={family}:{axes}&text={urllib.parse.quote(CHARSET)}"
    save_as_ttf(fetch(css_font_url(query)), name)


def icon_font() -> None:
    names = sorted(set(re.findall(r'=\s*"([a-z0-9_]+)"', SYMBOLS.read_text())))
    if not names:
        sys.exit("No icon names found in Symbols.kt")
    query = ("family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0..1,0"
             f"&icon_names={','.join(names)}")
    save_as_ttf(fetch(css_font_url(query)), "material_symbols_rounded.ttf")
    print(f"  {len(names)} icons")


if __name__ == "__main__":
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    only_icons = "--icons" in sys.argv
    if not only_icons:
        text_font("Roboto+Flex", "wght@300..800", "roboto_flex.ttf")
        text_font("Google+Sans+Flex", "wght@300..800", "google_sans_flex.ttf")
    icon_font()
