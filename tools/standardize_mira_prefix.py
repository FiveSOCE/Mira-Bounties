from pathlib import Path
import re

PREFIX = '&5&lMira &8>> &r'

resources = Path('src/main/resources')
if resources.exists():
    for p in resources.rglob('*.yml'):
        if p.name == 'plugin.yml':
            continue
        original = p.read_text()
        lines = original.splitlines()
        out = []
        found = False
        for line in lines:
            if re.match(r'^\s*prefix\s*:', line, re.I):
                indent = re.match(r'^(\s*)', line).group(1)
                line = f"{indent}prefix: '{PREFIX}'"
                found = True
            out.append(line)
        text = '\n'.join(out) + ('\n' if original.endswith('\n') else '')
        if p.name == 'config.yml' and not found:
            m = re.search(r'(?m)^messages:\s*$', text)
            if m:
                text = text[:m.end()] + f"\n  prefix: '{PREFIX}'" + text[m.end():]
            else:
                text = text.rstrip() + f"\n\nmessages:\n  prefix: '{PREFIX}'\n"
        if text != original:
            p.write_text(text)

helper = re.compile(
    r'((?:public|private|protected)?\s*(?:static\s+)?void\s+'
    r'(?:msg|message|sendMessage|broadcast)\s*\([^)]*String\s+(?P<var>\w+)'
    r'[^)]*\)\s*\{)(?P<body>[^{}]*)(\})',
    re.S,
)

java_root = Path('src/main/java')
if java_root.exists():
    for p in java_root.rglob('*.java'):
        original = p.read_text()
        text = re.sub(
            r'(getString\(\s*"(?:messages\.)?prefix"\s*,\s*)"(?:\\.|[^"\\])*"',
            lambda m: m.group(1) + '"' + PREFIX + '"',
            original,
        )
        text = re.sub(
            r'(?im)(\b(?:CHAT_)?PREFIX\b\s*=\s*)"(?:\\.|[^"\\])*"',
            lambda m: m.group(1) + '"' + PREFIX + '"',
            text,
        )

        def fix_helper(m):
            body = m.group('body')
            var = m.group('var')
            if 'prefix' in body.lower() or PREFIX in body:
                return m.group(0)
            if 'sendMessage' not in body and 'broadcastMessage' not in body:
                return m.group(0)
            pat = re.compile(
                r'((?:sendMessage|broadcastMessage)\s*\([^;]*?)(\b' + re.escape(var) + r'\b)',
                re.S,
            )
            body2, count = pat.subn(
                lambda x: x.group(1) + '"' + PREFIX + '" + ' + var,
                body,
                count=1,
            )
            return m.group(1) + body2 + m.group(4) if count else m.group(0)

        text = helper.sub(fix_helper, text)
        if text != original:
            p.write_text(text)
