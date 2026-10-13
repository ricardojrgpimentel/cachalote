#!/usr/bin/env python3
"""Helper para a dump do uiautomator: encontra nós e devolve coordenadas.

  ui.py <ui.xml> list                 # lista textos e bounds (debug)
  ui.py <ui.xml> find <texto>         # primeiro nó cujo texto/desc contém <texto>
  ui.py <ui.xml> find-exact <texto>
  ui.py <ui.xml> exists <texto>       # exit 0 se existir

Saída de find: "cx cy x1 y1 x2 y2"
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        return list(ET.parse(path).getroot().iter("node"))
    except Exception as e:
        print(f"erro a ler {path}: {e}", file=sys.stderr)
        return []


def haystack(n):
    return ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip()


def bounds(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x1, y1, x2, y2


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    path, cmd = sys.argv[1], sys.argv[2]
    needle = sys.argv[3] if len(sys.argv) > 3 else None
    ns = nodes(path)

    if cmd == "list":
        for n in ns:
            b = bounds(n)
            if haystack(n) and b:
                print(f"{b[0]},{b[1]},{b[2]},{b[3]}  {haystack(n)[:70]}")
        return 0

    if cmd == "exists":
        return 0 if any(needle.lower() in haystack(n).lower() for n in ns) else 1

    exact = cmd == "find-exact"
    for n in ns:
        h = haystack(n)
        hit = (h == needle) if exact else (needle.lower() in h.lower())
        if hit:
            b = bounds(n)
            if not b:
                continue
            cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            print(f"{cx} {cy} {b[0]} {b[1]} {b[2]} {b[3]}")
            return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
