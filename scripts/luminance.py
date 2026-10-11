#!/usr/bin/env python3
"""Check the contrast of the colour pairs D4 raises against WCAG AA.

A palette claim is only a claim until it is measured, and the raised secondary
and error roles plus the ten update-pill pairs are exactly the ones the review
found failing. The values are read out of AnswerStyle.kt, the file that paints
with them, so an edit there that undoes the raise fails this check instead of
shipping quietly; a copy kept here would drift and turn this into a report. The
method is WCAG 2.1 sRGB relative luminance: the channels are linearised,
weighted into a luminance, and compared as (lighter + 0.05) / (darker + 0.05).
AA is 4.5:1, and every pair here is small text, so the large-text exemption does
not apply.
"""

import re
import sys
from pathlib import Path

SOURCE = (
    Path(__file__).resolve().parent.parent
    / "app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt"
)

# The raised role pairs: which role, and the surface its colour lands on. D4
# scopes the raise to Midnight and Indigo; Amber, Forest and Light keep their
# own secondary and error, whose remaining miss is a recorded limitation.
RAISED = [
    ("Midnight", "onSurfaceVariant", "background"),
    ("Midnight", "onSurfaceVariant", "surfaceVariant"),
    ("Midnight", "error", "background"),
    ("Midnight", "error", "surfaceVariant"),
    ("Indigo", "onSurfaceVariant", "background"),
    ("Indigo", "onSurfaceVariant", "surfaceVariant"),
    ("Indigo", "error", "background"),
    ("Indigo", "error", "surfaceVariant"),
]

# Every scheme the pill rows cover, in the order AnswerStyle declares them. The
# four dark schemes share a pair; only Light takes the light pair.
SCHEME_ORDER = ["Midnight", "Indigo", "Amber", "Forest", "Light"]

# Every scheme must state these roles, or the check has nothing to measure.
ROLES = ("background", "surface", "surfaceVariant", "onSurfaceVariant", "error")

# A pill paints its label on its fill at this alpha over the scheme's surface.
PILL_ALPHA = 0.16

AA = 4.5


def call_body(source: str, open_paren: int) -> str:
    """The text between the '(' at open_paren and the ')' that closes it."""
    depth = 0
    for index in range(open_paren, len(source)):
        if source[index] == "(":
            depth += 1
        elif source[index] == ")":
            depth -= 1
            if depth == 0:
                return source[open_paren + 1 : index]
    raise ValueError("unbalanced parentheses: the source is not the file expected")


def to_hex(compressed: str) -> str:
    """The 8-digit ARGB literal Compose writes, as #RRGGBB with alpha dropped."""
    return "#" + compressed[-6:].upper()


def roles_in(body: str) -> dict:
    """The named colour roles a colorScheme(...) call states, as hex strings."""
    return {
        match.group(1): to_hex(match.group(2))
        for match in re.finditer(r"(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", body)
    }


def parse_palettes(source: str) -> dict:
    """Each theme's roles, keyed by the label its AnswerTheme carries."""
    palettes = {}
    for match in re.finditer(r"\bdarkColorScheme\s*\(", source):
        names = re.findall(r"val\s+(\w+)\s*=\s*AnswerTheme\s*\(", source[: match.start()])
        if not names:
            raise ValueError("a darkColorScheme is not inside an AnswerTheme")
        palettes[names[-1].capitalize()] = roles_in(call_body(source, match.end() - 1))

    light = re.search(r"\blightColorScheme\s*\(", source)
    if light is None:
        raise ValueError("no lightColorScheme in AnswerStyle.kt")
    palettes["Light"] = roles_in(call_body(source, light.end() - 1))

    for scheme, roles in palettes.items():
        missing = [role for role in ROLES if role not in roles]
        if missing:
            raise ValueError(f"{scheme} declares no {', '.join(missing)}")
    return palettes


def parse_pills(source: str, name: str) -> dict:
    """The two PillPairs of a PillColors literal: fill and label, by meaning."""
    match = re.search(rf"val\s+{name}\s*=\s*PillColors\s*\(", source)
    if match is None:
        raise ValueError(f"{name} is not declared in AnswerStyle.kt")
    pairs = re.findall(
        r"PillPair\(\s*Color\(0x([0-9A-Fa-f]{8})\)\s*,\s*Color\(0x([0-9A-Fa-f]{8})\)\s*\)",
        call_body(source, match.end() - 1),
    )
    if len(pairs) != 2:
        raise ValueError(f"{name} does not hold two PillPairs")
    return {
        "available": {"fill": to_hex(pairs[0][0]), "text": to_hex(pairs[0][1])},
        "upToDate": {"fill": to_hex(pairs[1][0]), "text": to_hex(pairs[1][1])},
    }


def build_rows(source: str) -> list:
    """(scheme, label, fg, tint, base, alpha, required) for every pair checked."""
    palettes = parse_palettes(source)
    dark_pills = parse_pills(source, "darkPills")
    light_pills = parse_pills(source, "lightPills")

    table = [
        (scheme, f"{role} on {over}", palettes[scheme][role], None, palettes[scheme][over], 1.0, True)
        for scheme, role, over in RAISED
    ]
    for scheme in SCHEME_ORDER:
        pills = light_pills if scheme == "Light" else dark_pills
        for label, pair in (("available pill", pills["available"]), ("up to date pill", pills["upToDate"])):
            table.append(
                (scheme, label, pair["text"], pair["fill"], palettes[scheme]["surface"], PILL_ALPHA, True)
            )
    return table


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
    rows = build_rows(SOURCE.read_text(encoding="utf-8"))
    failures = []
    for scheme, label, fg, tint, base, alpha, required in rows:
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

    print(f"\n{len(rows)} pairs, all at or above {AA}:1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
