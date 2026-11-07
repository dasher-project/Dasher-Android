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

def _catalogue_res_names():
    """Review I4: compare RES NAMES, not keys — 'default'/'new' map to
    s_default/s_new and key-comparison misclassified them app-only."""
    with open(SHARED) as f:
        shared = json.load(f)["shared"]
    return {res_name(k) for k in shared}


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
def _discover_app_only(current_default):
    if not current_default:
        return {"save_default_name"}
    names = set(re.findall(r'<string name="([^"]+)"', current_default))
    catalogue = _catalogue_res_names()
    return {n for n in names if n not in catalogue} or {"save_default_name"}


def generate_default(shared, current_default):
    """Default locale: catalogue `en` + the app-only strings (preserved from
    the existing file by key, so hand-added dev strings survive regen)."""
    app_only = _discover_app_only(current_default)
    _aliased = set(KEY_ALIASES.values())
    kept = {}
    if current_default:
        # Review M4: tolerate attributes (translatable="false" etc.)
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', current_default, re.S):
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


def selftest():
    """Review I5: pin the collision-prone mapping logic without a pytest harness."""
    locales, shared = load_catalogue()

    # res_name: keyword + digit-initial keys
    assert res_name("default") == "s_default"
    assert res_name("new") == "s_new"
    assert res_name("1_1f_cps_2_d_wpm") == "typing_rate"  # alias
    assert res_name("toolbar_new") == "toolbar_new"

    # no two catalogue keys map to the same res name unless explicitly skipped
    names = {}
    for k in shared:
        if k in GTK_ONLY_KEYS or k in ANDROID_SKIP_KEYS:
            continue
        n = res_name(k)
        assert n not in names, f"collision: {k} and {names[n]} -> {n}"
        names[n] = k

    # skipped keys resolve without collision with aliases
    for k in ANDROID_SKIP_KEYS:
        assert res_name(k) not in names or k in KEY_ALIASES.values(), f"skip key {k} collides"

    # default-file round-trip: generate from a seed, re-parse, regenerate —
    # the app-only preservation must survive a regen cycle unchanged, and
    # the catalogue half must be identical (review loop-2: the actual hazard).
    seed = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <string name="app_name">Dasher</string>\n'
            '    <string name="ime_name">Dasher Keyboard</string>\n'
            '    <string name="save_default_name">dasher.txt</string>\n'
            '</resources>\n')
    once = generate_default(shared, seed)
    twice = generate_default(shared, once)  # once IS the re-parsed file
    assert once == twice, "default-file generation not idempotent (app-only keys drift)"
    assert 'name="ime_name"' in once, "app-only key lost on first pass"

    # default-file discovery: res names, not keys. app_name IS catalogued
    # now, and 'default' catalogues as s_default — neither is app-only. A
    # genuinely app-only key (ime_name, not in the catalogue) is kept.
    fake_default = ('<resources><string name="app_name">Dasher</string>'
                    '<string name="s_default">Default</string>'
                    '<string name="ime_name">Dasher Keyboard</string></resources>')
    d = _discover_app_only(fake_default)
    assert "ime_name" in d, f"app-only key lost: {d}"
    assert "app_name" not in d and "s_default" not in d, f"catalogue keys misclassified as app-only: {d}"

    print("selftest OK: res-name mapping, alias/skip, idempotence, discovery")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--with-default", action="store_true",
                    help="also regenerate values/strings.xml (default locale)")
    ap.add_argument("--check", action="store_true",
                    help="exit 1 if any generated file differs from the working tree (CI gate)")
    ap.add_argument("--selftest", action="store_true",
                    help="run internal consistency checks (res-name mapping, alias/skip interplay, two-pass idempotence)")
    args = ap.parse_args()

    if args.check and not args.with_default:
        ap.error("--check requires --with-default (the default file is generated too; not checking it would pass silently)")

    if args.selftest:
        return selftest()

    locales, shared = load_catalogue()
    print(f"{len(shared)} keys, {len(locales)} locales")
    dirty = 0

    default_path = os.path.join(RES, "values", "strings.xml")
    if args.with_default:
        if not os.path.exists(default_path):
            # Review loop-2: refusing in write mode too — regenerating from
            # nothing silently wipes every app-only key (only save_default_name
            # would survive). Restore the file (git) and regenerate.
            print("REFUSING: values/strings.xml is missing — its app-only keys "
                  "(ime_name, toolbar_*, settings chrome) cannot be recovered from "
                  "the catalogue. Restore it from git and re-run.", file=sys.stderr)
            sys.exit(1)
        else:
            current = open(default_path).read()
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
