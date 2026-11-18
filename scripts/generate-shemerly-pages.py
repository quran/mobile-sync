#!/usr/bin/env python3
"""Export physical page ranges from the checksum-pinned Content word layout.

Usage: python3 scripts/generate-shemerly-pages.py /path/to/shemerly.words.v1.json
Then regenerate PageLayoutTables.kt and copy this JSON to users-backend's
apps/api/src/services/shemerly-page-starts.json on both release branches.
"""

import hashlib
import json
import sys
from pathlib import Path

WORD_SHA256 = "b3281926539114758c10f1617208900b0ee75b3474116b5fe5e80a676e94d4bc"
OUTPUT = Path(__file__).resolve().parent / "data/shemerly-pages.json"
SURA_VERSES = [
    7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135,
    112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53,
    89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12,
    12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26,
    30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
]


def build(raw: bytes) -> dict:
    if hashlib.sha256(raw).hexdigest() != WORD_SHA256:
        raise ValueError("Unreviewed Content word artifact checksum")
    words = json.loads(raw)
    memberships = {page: set() for page in range(1, 522)}
    assert [row[0] for row in words["verses"]] == list(range(1, 6237))
    for verse, _, placements in words["verses"]:
        for page, _, _ in placements:
            memberships[page].add(verse)
    ranges = [(min(ids), max(ids)) for ids in memberships.values()]
    assert all(ids == set(range(min(ids), max(ids) + 1)) for ids in memberships.values())
    assert sum(map(len, memberships.values())) == 6604
    assert sum(a[1] == b[0] for a, b in zip(ranges, ranges[1:])) == 368

    keys = [(sura, ayah) for sura, count in enumerate(SURA_VERSES, 1) for ayah in range(1, count + 1)]
    return {
        "description": "Shemerly 521 physical page ranges, including split ayahs; canonical Hafs numbering.",
        "source": {
            "url": "https://github.com/quran/quran.com-api/pull/1037",
            "path": "config/qdc/mushaf_content/shemerly.words.v1.json",
            "sha256": WORD_SHA256,
            "layout_database_sha256": words["source"]["database_sha256"],
            "android_artifact_sha256": words["source"]["android_artifact_sha256"],
        },
        "androidStartsCorrected": {
            "305": {"was": [25, 72], "now": [25, 73]},
            "438": {"was": [50, 24], "now": [50, 34]},
            "510": {"was": [89, 25], "now": [89, 24]},
        },
        "pageStarts": [keys[first - 1] for first, _ in ranges],
        "pageEnds": [keys[last - 1] for _, last in ranges],
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    OUTPUT.write_text(json.dumps(build(Path(sys.argv[1]).read_bytes()), indent=2) + "\n")
    print(f"Wrote {OUTPUT}: 521 pages, 6604 memberships, 368 split ayahs")
