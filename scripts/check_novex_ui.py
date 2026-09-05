#!/usr/bin/env python3
"""Enforce the product component boundary, including aliases and qualified calls."""
from pathlib import Path
import re
import sys

CONTROLS = {
    'AlertDialog', 'Button', 'OutlinedButton', 'TextButton', 'OutlinedTextField',
    'TopAppBar', 'DropdownMenu', 'DropdownMenuItem', 'ModalBottomSheet', 'Switch', 'Scaffold',
    'Card', 'RadioButton', 'Checkbox', 'FilterChip', 'AssistChip',
    'ListItem', 'SegmentedButton', 'SingleChoiceSegmentedButtonRow', 'Slider', 'FloatingActionButton',
}
PLATFORM_FILES = {'novex/NovexMaterialControls.kt', 'novex/NovexChoiceControls.kt'}

def violations(source, relative_path):
    if relative_path in PLATFORM_FILES:
        return []
    # Remove comments while retaining line numbers; aliases do not evade imports.
    source = re.sub(r'/\*.*?\*/|//[^\n]*', lambda m: '\n' * m[0].count('\n'), source, flags=re.S)
    pattern = re.compile(r'androidx\.compose\.material3\.(' + '|'.join(sorted(CONTROLS)) + r')\b|import\s+androidx\.compose\.material3\.\*')
    icons = re.compile(r'Icons\.(?:AutoMirrored\.)?(?:Default|Filled|Outlined|Rounded)\.\w+')
    decorations = re.compile(r'OutlinedTextFieldDefaults\.(?:DecorationBox|ContainerBox|Container)\s*\(')
    return [(source.count('\n', 0, m.start()) + 1, m[0]) for rx in (pattern, icons, decorations) for m in rx.finditer(source)]

def main():
    root = Path(__file__).resolve().parents[1] / 'src/android/app/src/main/java/com/openminis/app/ui'
    found = [(p.relative_to(root).as_posix(), n, text) for p in sorted(root.rglob('*.kt'))
             for n, text in violations(p.read_text(), p.relative_to(root).as_posix())]
    for path, line, text in found:
        print(f'{path}:{line}: {text}')
    print(f'Novex component boundary: {len(found)} violations')
    return bool(found)

if __name__ == '__main__':
    sys.exit(main())
