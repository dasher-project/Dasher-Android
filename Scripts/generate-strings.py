#!/usr/bin/env python3
"""Generate Android values-XX/strings.xml from the shared UI catalogue.

Android-owned localisation pipeline (#82): previously this generator lived
in Dasher-GTK and wrote cross-repo into ../Dasher-Android — Android couldn't
CI-gate its own freshness and the default values/strings.xml was hand-written.
Now vendored here with the shared-resources submodule; the build-freshness
gate lives in .github/workflows/strings-freshness.yml.

The shared catalogue (shared-resources/ui-strings.json) is the single source
of truth. Translators edit that file; regenerate with:

    python3 Scripts/generate-strings.py [--with-default]

--with-default also regenerates the DEFAULT values/strings.xml. The default
file carries a small set of app-defined strings that are not in the catalogue
(app_name, save_default_name, plus any new-not-yet-catalogued strings during
development) — those are preserved by key when regenerating, everything else
comes from the catalogue's `en`.

Extraction (frontend → catalogue) was a one-time bootstrap; translation edits
go into ui-strings.json directly.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SHARED = os.path.join(REPO, "shared-resources", "ui-strings.json")
RES = os.path.join(REPO, "app", "src", "main", "res")

# Keys other frontends own (mirrors GTK's generate-from-shared.py).
GTK_ONLY_KEYS = {
    "keyboard_mode_opacity", "keyboard_mode_title", "keyboard_mode_exit",
    "keyboard_mode_stopped", "keyboard_mode_description",
    "layout_description", "dwell_description",
    "help_about_dasher", "game_target_no_data",
}

# Catalogue key -> legacy Android resource name (avoids duplicate-format-string
# merges: the app historically used its own names for a few shared strings).
KEY_ALIASES = {
    "1_1f_cps_2_d_wpm": "typing_rate",  # legacy app name for the format string
}

# Catalogue keys Android skips: their names collide with legacy app-only
# strings carrying DIFFERENT content (the catalogue's "typing_rate" is the
# Settings-row label; the app's typing_rate is the %1$.1f cps format string).
ANDROID_SKIP_KEYS = {
    "typing_rate",  # label variant — Android's Settings rows stay app-defined for now
}

JAVA_KEYWORDS = {
    "default", "class", "int", "long", "boolean", "new",
    "public", "private", "static", "final", "switch",
    "case", "break", "continue", "return", "void", "if",
    "else", "for", "while", "do", "try", "catch", "this",
    "super", "import", "package", "true", "false", "null",
}


def load_catalogue():
    with open(SHARED) as f:
        data = json.load(f)
    return data["meta"]["locales"], data["shared"]

_CATALOGUE_KEYS = set()
if os.path.exists(SHARED):
    with open(SHARED) as _f:
        _CATALOGUE_KEYS = set(json.load(_f)["shared"].keys())


def xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace("'", "\\'"))


def android_locale_dir(locale):
    return "values-" + locale.replace("pt-PT", "pt-rPT").replace("zh-CN", "zh-rCN").replace("_", "-r")


def res_name(key):
    key = KEY_ALIASES.get(key, key)
    name = key if key[0].isalpha() else "s_" + key
    return "s_" + name if name in JAVA_KEYWORDS else name


def generate_xml(locale, shared):
    """Generate a values[-XX]/strings.xml body from the catalogue."""
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<resources>",
        "    <!-- GENERATED from shared-resources/ui-strings.json — do not edit directly.",
        "         Edit the catalogue and run Scripts/generate-strings.py. -->",
    ]
    count = 0
    for key in sorted(shared.keys()):
        if key in GTK_ONLY_KEYS or key in ANDROID_SKIP_KEYS:
            continue
        entry = shared[key]
        en_text = entry.get("en", "")
        if not en_text:
            continue
        translated = entry.get(locale, en_text)  # fallback to English
        lines.append(f'    <string name="{res_name(key)}">{xml_escape(translated)}</string>')
        if locale in entry:
            count += 1
    lines.append("</resources>")
    return "\n".join(lines) + "\n", count


# Keys the app defines outside the catalogue and must never lose:
# kept in the DEFAULT values/strings.xml across regenerations.
# App-defined strings the catalogue doesn't carry yet (ime_name, toolbar_*,
# settings chrome, always_docked…). Preserved from the existing default file
# BY KEY across regenerations — this file is Android's dev surface; strings
# graduate into the catalogue via ui-strings.json when they stabilise.
import re as _re
def _discover_app_only(current_default):
    if not current_default:
        return {"save_default_name"}
    names = set(_re.findall(r'<string name="([^"]+)"', current_default))
    return {n for n in names if n not in _CATALOGUE_KEYS} or {"save_default_name"}


def generate_default(shared, current_default):
    """Default locale: catalogue `en` + the app-only strings (preserved from
    the existing file by key, so hand-added dev strings survive regen)."""
    app_only = _discover_app_only(current_default)
    _aliased = set(KEY_ALIASES.values())
    kept = {}
    if current_default:
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', current_default, re.S):
            name, text = m.group(1), m.group(2)
            if name in app_only:
                kept[name] = text

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<resources>",
        "    <!-- GENERATED from shared-resources/ui-strings.json — do not edit directly.",
        "         Edit the catalogue and run Scripts/generate-strings.py.",
        "         App-only keys (not yet in the catalogue) are preserved from this file. -->",
    ]
    for name in sorted(app_only):
        if name in kept:
            lines.append(f'    <string name="{name}">{kept[name]}</string>')
    for key in sorted(shared.keys()):
        if key in GTK_ONLY_KEYS or key in ANDROID_SKIP_KEYS:
            continue
        if res_name(key) in app_only:
            continue  # the app-only file carries this name already
        en_text = shared[key].get("en", "")
        if not en_text:
            continue
        lines.append(f'    <string name="{res_name(key)}">{xml_escape(en_text)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--with-default", action="store_true",
                    help="also regenerate values/strings.xml (default locale)")
    ap.add_argument("--check", action="store_true",
                    help="exit 1 if any generated file differs from the working tree (CI gate)")
    args = ap.parse_args()

    locales, shared = load_catalogue()
    print(f"{len(shared)} keys, {len(locales)} locales")
    dirty = 0

    default_path = os.path.join(RES, "values", "strings.xml")
    if args.with_default:
        current = open(default_path).read() if os.path.exists(default_path) else ""
        content = generate_default(shared, current)
        if args.check:
            if os.path.exists(default_path) and open(default_path).read() != content:
                print("STALE: values/strings.xml"); dirty += 1
        else:
            open(default_path, "w").write(content)
            print("  values/strings.xml regenerated (default)")

    for locale in locales:
        if locale == "en":
            continue
        content, count = generate_xml(locale, shared)
        path = os.path.join(RES, android_locale_dir(locale), "strings.xml")
        if args.check:
            if not os.path.exists(path) or open(path).read() != content:
                print(f"STALE: {os.path.relpath(path, REPO)}"); dirty += 1
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w").write(content)
            print(f"  {locale:7s} {count}/{len(shared)} translated")

    if args.check:
        if dirty:
            print(f"\n{dirty} stale file(s) — run Scripts/generate-strings.py and commit")
            sys.exit(1)
        print("all generated strings fresh")
    else:
        print("done.")


if __name__ == "__main__":
    main()
