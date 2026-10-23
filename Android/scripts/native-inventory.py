#!/usr/bin/env python3
"""Inventory tracked prebuilt SOs; transitive AARs require a separate resolved scan."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import struct


def load_alignment(data):
    if data[:4] != b"\x7fELF":
        return []
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 54)
        layout = endian + "IIQQQQQQ"
    else:
        offset = struct.unpack_from(endian + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 42)
        layout = endian + "IIIIIIII"
    alignments = []
    for index in range(count):
        segment = struct.unpack_from(layout, data, offset + index * entry_size)
        if segment[0] == 1:  # PT_LOAD
            alignments.append(segment[-1])
    return alignments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    rows = []
    for source in (root / "app/libs", root / "dokit-gps-mock/libs"):
        for file in sorted(source.rglob("*.so")):
            data = file.read_bytes()
            alignments = load_alignment(data)
            rows.append(dict(source=str(file.relative_to(root)), abi=file.parent.name,
                             name=file.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                             load_alignments=alignments,
                             load_alignment_16k=bool(alignments) and min(alignments) >= 16384))
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[(row["abi"], row["name"])].append(row)
    report = dict(scope="app/GPS prebuilt SOs only; ELF alignment does not prove device compatibility",
                  files=rows, duplicates=[dict(abi=key[0], name=key[1],
                    identical=len({r["sha256"] for r in values}) == 1,
                    sources=[r["source"] for r in values])
                    for key, values in sorted(grouped.items()) if len(values) > 1])
    output = args.output or root / "build/reports/migration/native-inventory.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"{len(rows)} SOs; {len(report['duplicates'])} duplicate groups; "
          f"{sum(not row['load_alignment_16k'] for row in rows)} below 16 KB ELF alignment; {output}")


if __name__ == "__main__":
    main()
