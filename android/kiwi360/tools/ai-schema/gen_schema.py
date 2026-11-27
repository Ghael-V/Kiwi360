# SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
# SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
"""Builds the AI per-game settings schema from Xenia Edge's cvars and tiers.json.

    python -I tools/ai-schema/gen_schema.py <xenia-edge> app/src/main/res/raw/ai_settings_schema.json
    python -I tools/ai-schema/gen_schema.py <xenia-edge> --list
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFINE = re.compile(
    r'DEFINE_(?:transient_)?(bool|int32|uint32|int64|uint64|double|string|path)\s*\(\s*([A-Za-z0-9_]+)\s*,(.*?)\)\s*;',
    re.S)
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
# The Android launcher passes cvars as typed Bundle extras; int64 and double have no
# getter the frontend uses, so they can't be set per game.
SETTABLE = {'bool': 'bool', 'int32': 'int', 'uint32': 'int', 'string': 'string'}


def unescape(text):
    return text.replace('\\n', ' ').replace('\\"', '"').replace('\\\\', '\\')


def read_cvars(root):
    cvars = {}
    for folder, _, files in os.walk(os.path.join(root, 'src')):
        for name in files:
            if not name.endswith(('.cc', '.cpp', '.h')):
                continue
            path = os.path.join(folder, name)
            with open(path, encoding='utf-8', errors='replace') as f:
                text = f.read()
            for m in DEFINE.finditer(text):
                kind, key, rest = m.groups()
                strings = STRING.findall(rest)
                if kind in ('string', 'path'):
                    default = unescape(strings[0]) if strings else ''
                    description = ''.join(strings[1:-1])
                else:
                    default = rest.split(',')[0].strip()
                    description = ''.join(strings[:-1])
                category = strings[-1] if strings else ''
                description = re.sub(r'\s+', ' ', unescape(description)).strip()
                # Platform variants (apu, gpu...) repeat a name: the first one is enough.
                cvars.setdefault(key, {'kind': kind, 'default': default, 'description': description,
                                       'category': category, 'file': os.path.relpath(path, root)})
    return cvars


def entry(key, tier, cvar, tiers):
    override = tiers.get('types', {}).get(key, {})
    kind = override.get('type') or SETTABLE.get(cvar['kind'])
    if kind is None:
        raise SystemExit(f'{key}: {cvar["kind"]} cvars cannot be set per game')
    description = tiers.get('descriptions', {}).get(key) or cvar['description']
    if len(description) > 420:
        description = description[:417].rsplit(' ', 1)[0] + '...'
    out = {'key': key, 'tier': tier, 'source': 'hidden', 'cvar_type': SETTABLE[cvar['kind']]}
    default = override.get('default', cvar['default'])
    if kind == 'bool':
        if default not in ('true', 'false'):
            default = 'true' if default in ('1',) else 'false' if default in ('0',) else None
            if default is None:
                raise SystemExit(f'{key}: default {cvar["default"]!r} needs an override')
        out.update(type='bool', default=default)
    elif kind == 'string':
        values = override.get('values')
        if not values:
            raise SystemExit(f'{key}: string cvars need their values in tiers.json')
        if default not in values:
            raise SystemExit(f'{key}: default {default!r} is not one of {values}')
        # The server and the model work with integers: a string cvar is an enum of its values.
        out.update(type='enum', default=str(values.index(default)),
                   enum=[{'name': v, 'value': i} for i, v in enumerate(values)])
    else:
        try:
            number = int(default, 0)
        except ValueError:
            raise SystemExit(f'{key}: default {default!r} is not a number; add one to tiers.json')
        out['default'] = str(number)
        if 'enum' in override:
            out.update(type='enum', enum=override['enum'])
        elif 'min' in override and 'max' in override:
            out.update(type='int', min=str(override['min']), max=str(override['max']))
        else:
            raise SystemExit(f'{key}: integer cvars need min/max or an enum in tiers.json')
    out['description'] = description
    return out


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    cvars = read_cvars(sys.argv[1])
    with open(os.path.join(HERE, 'tiers.json'), encoding='utf-8') as f:
        tiers = json.load(f)
    tiered = set(tiers['allow']) | set(tiers['risky']) | set(tiers['preference']) | set(tiers['never_reasons'])
    if sys.argv[2] == '--list':
        for key in sorted(cvars):
            c = cvars[key]
            mark = 'allow' if key in tiers['allow'] else 'risky' if key in tiers['risky'] else \
                'preference' if key in tiers['preference'] else 'never'
            print(f'{mark:10} {c["category"]:10} {c["kind"]:7} {key} = {c["default"]}')
        return
    for key in tiered:
        if key not in cvars:
            raise SystemExit(f'tiers.json names {key}, which is no cvar')
    settings = []
    for app in tiers['app']:
        out = {'key': app['key'], 'tier': 'allow', 'source': 'app', 'type': app['type'] if 'enum' not in app else 'enum',
               'default': app['default'], 'ui_title': app['ui_title'], 'description': app['description']}
        if 'enum' in app:
            out['enum'] = app['enum']
        settings.append(out)
    for tier in ('allow', 'risky'):
        for key in tiers[tier]:
            settings.append(entry(key, tier, cvars[key], tiers))
    schema = {'app': 'kiwi360', 'schema_version': tiers['schema_version'], 'settings': settings}
    with open(sys.argv[2], 'w', encoding='utf-8', newline='\n') as f:
        json.dump(schema, f, ensure_ascii=False, indent=1)
        f.write('\n')
    allow = sum(1 for s in settings if s['tier'] == 'allow')
    print(f'{len(settings)} settings: {allow} allow, {len(settings) - allow} risky')


if __name__ == '__main__':
    main()
