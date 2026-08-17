#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def parse_patch(path: Path):
    lines = path.read_text(encoding="utf-8").splitlines()
    current_file = None
    current_hunk = None
    result: list[tuple[str, list[tuple[list[str], list[str]]]]] = []

    def flush_hunk():
        nonlocal current_hunk
        if current_hunk is not None:
            assert current_file is not None
            result[-1][1].append(current_hunk)
            current_hunk = None

    for line in lines:
        if line.startswith("diff --git a/"):
            flush_hunk()
            parts = line.split(" ")
            if len(parts) != 4 or not parts[2].startswith("a/") or not parts[3].startswith("b/"):
                raise RuntimeError(f"Unsupported diff header: {line}")
            old_path = parts[2][2:]
            new_path = parts[3][2:]
            if old_path != new_path:
                raise RuntimeError(f"Rename not supported: {line}")
            current_file = old_path
            result.append((current_file, []))
            continue
        if line.startswith("--- a/") or line.startswith("+++ b/"):
            continue
        if line == "@@":
            flush_hunk()
            if current_file is None:
                raise RuntimeError("Hunk before file header")
            current_hunk = ([], [])
            continue
        if current_hunk is None:
            if line.strip():
                raise RuntimeError(f"Unexpected patch line outside hunk: {line!r}")
            continue

        old, new = current_hunk
        if line == "":
            # The supplied patch has two blank context lines without the normal leading space.
            old.append("")
            new.append("")
        elif line.startswith(" "):
            old.append(line[1:])
            new.append(line[1:])
        elif line.startswith("-"):
            old.append(line[1:])
        elif line.startswith("+"):
            new.append(line[1:])
        elif line.startswith("\\ No newline at end of file"):
            pass
        else:
            raise RuntimeError(f"Unsupported patch line: {line!r}")

    flush_hunk()
    return result


def find_subsequence(lines: list[str], needle: list[str]) -> list[int]:
    if not needle:
        return []
    n = len(needle)
    return [i for i in range(len(lines) - n + 1) if lines[i : i + n] == needle]


def apply_patch(path: Path) -> None:
    for relative, hunks in parse_patch(path):
        target = Path(relative)
        if not target.is_file():
            raise RuntimeError(f"Target file does not exist: {relative}")
        text = target.read_text(encoding="utf-8")
        had_final_newline = text.endswith("\n")
        lines = text.splitlines()

        for index, (old, new) in enumerate(hunks, start=1):
            matches = find_subsequence(lines, old)
            if len(matches) != 1:
                preview = "\n".join(old[:8])
                raise RuntimeError(
                    f"{path.name}: {relative} hunk {index}: expected one exact match, "
                    f"found {len(matches)}. Context starts with:\n{preview}"
                )
            start = matches[0]
            lines[start : start + len(old)] = new

        rendered = "\n".join(lines)
        if had_final_newline:
            rendered += "\n"
        target.write_text(rendered, encoding="utf-8")
        print(f"Applied {len(hunks)} hunk(s) to {relative}")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: apply_context_patch.py PATCH [PATCH ...]", file=sys.stderr)
        return 2
    for arg in sys.argv[1:]:
        apply_patch(Path(arg))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
