"""Mechanical crops of Codex image-model originals into 16x16 seasonal sprites.

This script draws no pixels. It only crops and uses point sampling to resize.
Run from the repository root: python scripts/art_cherry_pilot.py
"""

from pathlib import Path
import json
import subprocess


ROOT = Path(__file__).resolve().parents[1]
ORIGINALS = ROOT / "art" / "originals"
OUTPUT = ROOT / "src/main/resources/assets/better_seasonal_trees/textures/block"
MANIFEST = ROOT / "art" / "processing-manifest.json"


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or manifest.get("size") != [16, 16] or manifest.get("filter") != "point":
        raise ValueError(f"Unsupported art processing manifest: {MANIFEST}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for asset in manifest["assets"]:
        name = asset["name"]
        source = asset["source"]
        crop = asset["crop"]
        target = OUTPUT / f"{name}.png"
        command = ["gm", "convert", str(ORIGINALS / source), "-crop", crop,
                   "+repage", "-filter", manifest["filter"],
                   "-resize", f"{manifest['size'][0]}x{manifest['size'][1]}!"]
        for color in asset.get("alphaKeys", []):
            # Keys only listed image-model edge artifacts; this step never draws pixels.
            command += ["-fuzz", f"{asset.get('alphaFuzz', 8)}%", "-transparent", color]
        subprocess.run(command + [str(target)], check=True)
        print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
