#!/usr/bin/env python3
"""Check active experimental-playtest roster coverage and pinned source identities.

Usage: python3 scripts/catalogue_inventory.py [--mods-dir PATH]
The optional jar audit verifies pinned hashes, actual Forge mod IDs, and species paths.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs/implementation-contract.md"
INVENTORY = ROOT / "catalogue-draft/inventory.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def contract_roster(text, prefix):
    line = next((line for line in text.splitlines() if line.startswith(prefix)), None)
    require(line is not None, f"contract line missing: {prefix}")
    groups = line[len(prefix):].strip().split(".", 1)[0].split(";")
    result = []
    for group in groups:
        namespace, separator, names = group.strip().partition(" ")
        require(separator and names, f"invalid contract group: {group}")
        result.extend(f"{namespace}:{name}" for name in names.split("/"))
    return result


def unique(values, label):
    require(len(values) == len(set(values)), f"duplicate {label}")
    return set(values)


def check_inventory(data, contract):
    require(data.get("status") == "activated-experimental-playtest", "inventory status is not current")
    require(data.get("defaultPolicy") == "UNCHANGED", "unlisted species must remain unchanged")
    temperate = unique(data["temperate"], "temperate species")
    tropical = unique(data["tropicalDry"], "tropical species")
    require(temperate == set(contract_roster(contract, "31 temperate variants:")),
            "temperate inventory differs from approved contract")
    require(tropical == set(contract_roster(contract, "Dry-deciduous4:")),
            "tropical inventory differs from approved contract")
    require(len(temperate) == 31 and len(tropical) == 4, "approved roster counts differ")
    require(not temperate & tropical, "species appears in two habits")

    evergreen_line = next(line for line in contract.splitlines() if line.startswith("Evergreen blossoms:"))
    evergreen_names = {name.strip().lower() for name in evergreen_line.split(":", 1)[1].split(",", 1)[0].split("/")}
    verified = unique(data["evergreenBlossomVerifiedJarPaths"], "evergreen candidate")
    unresolved = unique(data["evergreenBlossomUnresolved"], "unresolved evergreen candidate")
    require({value.split(":", 1)[1] for value in verified} | unresolved == evergreen_names,
            "evergreen candidates do not cover approved names")
    require(not ({value.split(":", 1)[1] for value in verified} & unresolved),
            "evergreen candidate is both verified and unresolved")
    require(not (temperate | tropical) & verified, "evergreen candidate overlaps deciduous roster")

    unchanged = unique(data["explicitUnchangedVerifiedJarPaths"], "unchanged candidate")
    require({"dtnatures_spirit:ghaf", "dtnatures_spirit:saxaul",
             "dtnatures_spirit:coconut", "dtfruitsdelight:bayberry"} <= unchanged,
            "explicit unchanged cases missing")
    require("clove" in data["explicitUnchangedUnresolved"], "clove preservation not recorded")
    require("dtaether" in data["excludedNamespaces"], "Aether addon exclusion missing")
    require(not (temperate | tropical | verified) & unchanged, "unchanged candidate overlaps active policy")

    records = data["pinnedModRecords"]
    mod_ids = unique([entry["modId"] for entry in records], "pinned mod ID")
    covered = {value.split(":", 1)[0] for value in temperate | tropical | verified | unchanged}
    require(covered <= mod_ids, "species namespace has no pinned mod record")
    require(set(data["excludedNamespaces"]) <= mod_ids, "excluded namespace has no pinned mod record")

    active_files = list((ROOT / "src/main/resources/data").glob("*/seasonal_trees/**/*.json"))
    profiles = [json.loads(path.read_text(encoding="utf-8")) for path in active_files]
    active = {profile["speciesId"] for profile in profiles}
    expected = temperate | tropical | verified | unchanged | {"dynamictrees:spruce"}
    require(len(active_files) == len(expected) == 48, "active catalogue profile count differs from roster")
    require(active == expected, "active catalogue differs from approved verified roster")
    for profile in profiles:
        species = profile["speciesId"]
        namespace = species.split(":", 1)[0]
        expected_habit = ("TEMPERATE" if species in temperate else
                          "TROPICAL" if species in tropical else
                          "EVERGREEN" if species in verified else "UNCHANGED")
        require(profile["habit"] == expected_habit, f"habit mismatch: {species}")
        require("dynamictrees" in profile["requiredMods"], f"base DT dependency missing: {species}")
        require(profile.get("optionalMods", []) == ([] if namespace == "dynamictrees" else [namespace]),
                f"optional addon dependency mismatch: {species}")
    require(len(list((ROOT / "catalogue-draft").rglob("*.json"))) == 1,
            "draft contains additional JSON that may need review")
    return records, expected


def check_jars(mods_dir, records, species):
    by_mod = {}
    for record in records:
        filename = record["file"]
        require(Path(filename).name == filename, f"unsafe jar filename: {filename}")
        path = mods_dir / filename
        require(path.is_file(), f"pinned jar missing: {filename}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        require(digest == record["sha256"], f"pinned jar hash changed: {filename}")
        with zipfile.ZipFile(path) as archive:
            toml = archive.read("META-INF/mods.toml").decode("utf-8")
            ids = set(re.findall(r'^modId\s*=\s*["\']([^"\']+)["\']', toml, re.MULTILINE))
            require(record["modId"] in ids, f"Forge mod ID mismatch: {filename}")
            by_mod[record["modId"]] = set(archive.namelist())
    for value in species:
        namespace, name = value.split(":", 1)
        require(f"trees/{namespace}/species/{name}.json" in by_mod[namespace],
                f"pinned jar lacks species path: {value}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods-dir", type=Path, help="directory containing the pinned addon jars")
    args = parser.parse_args()
    data = json.loads(INVENTORY.read_text(encoding="utf-8"))
    records, species = check_inventory(data, CONTRACT.read_text(encoding="utf-8"))
    if args.mods_dir is not None:
        check_jars(args.mods_dir, records, species)
    print(f"PASS active inventory: 31 temperate, 4 tropical, 8 evergreen flowers, "
          f"5 explicit unchanged; "
          f"{len(records)} pinned mod IDs" + (" and jar hashes/paths" if args.mods_dir else ""))
    print("Unprofiled unresolved IDs: cinnamon and clove; default UNCHANGED")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, StopIteration) as error:
        print(f"FAIL active inventory: {error}", file=sys.stderr)
        sys.exit(1)
