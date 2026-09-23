#!/usr/bin/env python3
"""Convert the PrusaSlicer 3.x preset repository into the JSON the app reads.

The repository under app/src/main/assets/prusa/resources/presets/<repo>/<Vendor>/ is
the vendor bundle's own format: one or more YAML documents per file, where a
preset's values live behind conditions on the hardware (printer model, nozzle
diameter, MMU and sheet). The engine's console only accepts an already-resolved
configuration, so the app has to resolve those conditions itself; this script
only re-expresses the documents as JSON, so the app needs no YAML parser.

Writes app/src/main/assets/prusa-presets.json. Fails loudly when a document is
not a mapping, when a file cannot be parsed, or when the result looks empty.
"""
import argparse
import glob
import json
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the fetch script installs it
    sys.exit("PyYAML is required: python3 -m pip install --user pyyaml")


def documents(root):
    files = sorted(glob.glob(os.path.join(root, "*.yaml")))
    if not files:
        sys.exit("No preset YAML files under " + root)
    for path in files:
        name = os.path.basename(path)
        with open(path, encoding="utf-8") as handle:
            for index, document in enumerate(yaml.safe_load_all(handle)):
                if document is None:
                    continue
                if not isinstance(document, dict):
                    sys.exit(name + " document " + str(index) + " is not a mapping")
                document = dict(document)
                document["_file"] = name
                document["_index"] = index
                yield document


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", help="app assets directory, e.g. app/src/main/assets")
    args = parser.parse_args()

    preset_root = os.path.join(args.assets, "prusa/resources/presets")
    roots = sorted(glob.glob(os.path.join(preset_root, "*/*/")))
    if not roots:
        sys.exit("No vendor preset directory under " + preset_root)

    vendors = {}
    all_documents = []
    for root in roots:
        documents_here = list(documents(root))
        all_documents.extend(documents_here)
        for document in documents_here:
            if document.get("kind") == "vendor":
                vendors[document.get("id")] = document

    ids = [d["id"] for d in all_documents if isinstance(d.get("id"), str)]
    dangling = []
    for document in all_documents:
        for parent in document.get("inherits") or []:
            if parent not in ids:
                dangling.append(document.get("_file") + " inherits " + str(parent))
    if dangling:
        sys.exit("Unresolved preset references: " + "; ".join(sorted(set(dangling))[:10]))

    counts = {}
    for document in all_documents:
        counts[document.get("kind")] = counts.get(document.get("kind"), 0) + 1
    conditions = set()
    for document in all_documents:
        stack = [document]
        while stack:
            node = stack.pop()
            if node.get("condition"):
                conditions.add(node["condition"])
            stack.extend(node.get("variants") or [])

    out = os.path.join(args.assets, "prusa-presets.json")
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "vendor": sorted(vendors.values(), key=lambda d: str(d.get("id"))),
                "documents": all_documents,
            },
            handle,
            separators=(",", ":"),
            sort_keys=True,
        )
    size = os.path.getsize(out)
    print("Prusa presets: " + str(len(all_documents)) + " documents " + str(counts))
    print("  vendors: " + ", ".join(sorted(vendors)))
    print("  distinct conditions: " + str(len(conditions)))
    print("  wrote " + out + " (" + str(size // 1024) + " KiB)")


if __name__ == "__main__":
    main()
