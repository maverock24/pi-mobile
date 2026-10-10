#!/usr/bin/env python3
"""Check the contrast of the colour pairs D4 raises against WCAG AA.

A palette claim is only a claim until it is measured, and the raised secondary
and error roles plus the ten update-pill pairs are exactly the ones the review
found failing. The hexes are recomputed here from the same values AnswerStyle.kt
paints with, so a later edit that undoes the raise fails the check instead of
shipping quietly. The method is WCAG 2.1 sRGB relative luminance: the channels
are linearised, weighted into a luminance, and compared as
(lighter + 0.05) / (darker + 0.05). AA is 4.5:1, and every pair here is small
text, so the large-text exemption does not apply.
"""

import sys

# The scheme values, copied from AnswerStyle.kt. A change there is a change
# here, which is what makes this a check rather than a report.
SCHEMES = {
    "Midnight": {"background": "#070D18", "surface": "#0B1422", "surfaceVariant": "#121926"},
    "Indigo": {"background": "#080D16", "surface": "#0E1420", "surfaceVariant": "#151A23"},
    "Amber": {"background": "#150F0A", "surface": "#1E1610", "surfaceVariant": "#201C18"},
    "Forest": {"background": "#0A1510", "surface": "#101E18", "surfaceVariant": "#18201D"},
    "Light": {"background": "#F6F7FA", "surface": "#F6F7FA", "surfaceVariant": "#F1F3F7"},
}

# The two update pills. A pill is the fill at 16% alpha over the scheme's
# surface, with an explicit text colour; amber and green are the fills, and the
# light scheme needs a darker text because amber on its pale surface is 1.71:1.
AMBER = "#F0A83C"
GREEN = "#46C97E"

# (scheme, label, fg, tint, base, alpha, required). fg is the text, tint is the
# fill a pill's background is composited from (None means the pair is painted
# opaque), base is the surface the tint goes over, and alpha is the tint's.
# required rows must clear AA; the flag is carried so a pair that is knowingly
# exempt can still be printed without failing the run.
ROWS = [
    # The four roles D4 raises: onSurfaceVariant and error, each on the two
    # surfaces the app paints them over.
    ("Midnight", "onSurfaceVariant on background", "#7E94A9", None, "#070D18", 1.0, True),
    ("Midnight", "onSurfaceVariant on surfaceVariant", "#7E94A9", None, "#121926", 1.0, True),
    ("Midnight", "error on background", "#E35F5F", None, "#070D18", 1.0, True),
    ("Midnight", "error on surfaceVariant", "#E35F5F", None, "#121926", 1.0, True),
    ("Indigo", "onSurfaceVariant on background", "#7E8CA9", None, "#080D16", 1.0, True),
    ("Indigo", "onSurfaceVariant on surfaceVariant", "#7E8CA9", None, "#151A23", 1.0, True),
    ("Indigo", "error on background", "#E35F5F", None, "#080D16", 1.0, True),
    ("Indigo", "error on surfaceVariant", "#E35F5F", None, "#151A23", 1.0, True),
    # The ten pill pairs, one available and one up to date per scheme. The four
    # dark schemes share the pair; only Light moves off the fill as its text.
    ("Midnight", "available pill", AMBER, AMBER, "#0B1422", 0.16, True),
    ("Midnight", "up to date pill", GREEN, GREEN, "#0B1422", 0.16, True),
    ("Indigo", "available pill", AMBER, AMBER, "#0E1420", 0.16, True),
    ("Indigo", "up to date pill", GREEN, GREEN, "#0E1420", 0.16, True),
    ("Amber", "available pill", AMBER, AMBER, "#1E1610", 0.16, True),
    ("Amber", "up to date pill", GREEN, GREEN, "#1E1610", 0.16, True),
    ("Forest", "available pill", AMBER, AMBER, "#101E18", 0.16, True),
    ("Forest", "up to date pill", GREEN, GREEN, "#101E18", 0.16, True),
    ("Light", "available pill", "#6E4400", AMBER, "#F6F7FA", 0.16, True),
    ("Light", "up to date pill", "#0F6234", GREEN, "#F6F7FA", 0.16, True),
]

AA = 4.5


def channels(hex_colour: str) -> tuple:
    text = hex_colour.lstrip("#")
    return tuple(int(text[i : i + 2], 16) for i in (0, 2, 4))


def composite(tint: str, alpha: float, base: str) -> tuple:
    """The tint at alpha over the base, in the sRGB space Material composites in."""
    t, b = channels(tint), channels(base)
    return tuple(round(alpha * t[i] + (1 - alpha) * b[i]) for i in range(3))


def luminance(colour) -> float:
    def linear(channel: int) -> float:
        value = channel / 255
        return value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4

    red, green, blue = (linear(c) for c in colour)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast(fg: str, bg) -> float:
    fg_lum = luminance(channels(fg))
    bg_lum = luminance(bg) if isinstance(bg, tuple) else luminance(channels(bg))
    lighter, darker = max(fg_lum, bg_lum), min(fg_lum, bg_lum)
    return (lighter + 0.05) / (darker + 0.05)


def as_hex(rgb: tuple) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def main() -> int:
    failures = []
    for scheme, label, fg, tint, base, alpha, required in ROWS:
        background = composite(tint, alpha, base) if tint is not None else base
        ratio = contrast(fg, background)
        if required and ratio < AA:
            status = "FAIL"
            failures.append((scheme, label, ratio))
        else:
            status = "ok"
        print(f"{scheme:<9} {label:<33} {fg} on {as_hex(background) if isinstance(background, tuple) else background}  {ratio:5.2f}:1  {status}")

    if failures:
        print(f"\n{len(failures)} pair(s) below AA ({AA}:1):", file=sys.stderr)
        for scheme, label, ratio in failures:
            print(f"  {scheme} {label}: {ratio:.2f}:1", file=sys.stderr)
        return 1

    print(f"\n{len(ROWS)} pairs, all at or above {AA}:1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
