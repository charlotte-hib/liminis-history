"""Writes the public sample's slice of the fetched dataset (sample/dataset/), run by `wb sample`.

The slice holds the people written by hand in sample/people.jsonl, and for each of its moments the people most written
about who are tied to the moment's place and alive during it, so that the sample's lists are long enough to rank and
draw from. With --written-only, `wb slice` writes the people written by hand alone, for the data repository: they get
their places and articles, and no unreviewed person reaches the site. With them: their Wikipedia articles, their places, and their occupations with three levels of classes.
Lines are copied as the export wrote them; the same export makes the same files. The file names are DatasetFile's
(backend/core): keep them in step.

Usage: python3 -I sample_dataset.py [--written-only] <export dir> <release dir>
"""

import gzip
import json
import sys
from pathlib import Path

PER_MOMENT = 30
CLASS_LEVELS = 3
BIRTH = "P19"


def read_lines(path):
    with gzip.open(path, "rt", encoding="utf-8") as lines:
        return [(line, json.loads(line)) for line in lines]


def write_lines(path, lines):
    # No file name and no time in the header, so the bytes depend on the lines only.
    with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as out:
        for line in lines:
            out.write(line.encode("utf-8"))
    return len(lines)


def country(place):
    if place is None:
        return None
    return place.get("coordinatesCountry") or next((c["iso"] for c in place.get("countries", []) if c.get("iso")), None)


def main(export_dir, sample_dir, written_only=False):
    people = read_lines(export_dir / "people.jsonl.gz")
    places = {row["qid"]: (line, row) for line, row in read_lines(export_dir / "places.jsonl.gz")}

    def has_region(row):
        birth = next((p["place"] for p in row["places"] if p["property"] == BIRTH), None)
        return country(places.get(birth, (None, None))[1]) is not None

    written = {json.loads(line).get("qid") for line in (sample_dir / "people.jsonl").open(encoding="utf-8")}
    chosen = {row["qid"] for _, row in people if row["qid"] in written}
    for moment_file in [] if written_only else sorted((sample_dir / "moments").glob("*.json")):
        moment = json.loads(moment_file.read_text(encoding="utf-8"))
        place = moment.get("placeQid")
        if place is None:
            continue
        tied = [
            row for _, row in people
            if row["qid"] not in written and has_region(row)
            and row["born"] <= moment["end"] and row["died"] >= moment["start"]
            and any(p["place"] == place for p in row["places"])
        ]
        tied.sort(key=lambda row: (-row["sitelinks"], row["qid"]))
        chosen.update(row["qid"] for row in tied[:PER_MOMENT])

    people = [(line, row) for line, row in people if row["qid"] in chosen]
    articles = [line for line, row in read_lines(export_dir / "articles.jsonl.gz") if row["person"] in chosen]
    place_ids = {p["place"] for _, row in people for p in row["places"]}

    occupations = {row["qid"]: (line, row) for line, row in read_lines(export_dir / "occupations.jsonl.gz")}
    occupation_ids = set()
    level = {o["occupation"] for _, row in people for o in row["occupations"]}
    for _ in range(CLASS_LEVELS + 1):
        occupation_ids |= level
        level = {parent for qid in level if qid in occupations for parent in occupations[qid][1]["parents"]} - occupation_ids

    out = sample_dir / "dataset"
    out.mkdir(exist_ok=True)
    manifest = json.loads((export_dir / "manifest.json").read_text(encoding="utf-8"))
    manifest["counts"] = {
        "people": write_lines(out / "people.jsonl.gz", [line for line, _ in people]),
        "articles": write_lines(out / "articles.jsonl.gz", articles),
        "places": write_lines(out / "places.jsonl.gz", [line for qid, (line, _) in places.items() if qid in place_ids]),
        "occupations": write_lines(
            out / "occupations.jsonl.gz", [line for qid, (line, _) in occupations.items() if qid in occupation_ids]
        ),
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    missing = written - chosen - {None}
    print(f"Wrote {out}: {manifest['counts']}")
    if missing:
        print(f"Not in the dataset, written by hand only: {', '.join(sorted(missing))}")


if __name__ == "__main__":
    args = sys.argv[1:]
    written_only = "--written-only" in args
    paths = [arg for arg in args if arg != "--written-only"]
    main(Path(paths[0]), Path(paths[1]), written_only)
