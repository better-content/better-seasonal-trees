"""Pack-compatible source identity; mirrors ReleasePipeline.kt productionFingerprint."""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEST_TREES = {'test', 'tests', 'gametest', 'gameTest', 'testFixtures'}


def production_input(path: str) -> bool:
    parts = path.split('/')
    if parts[0] == 'src':
        return len(parts) < 2 or parts[1] not in TEST_TREES
    if parts[0] in {'build', '.gradle', '.git', 'docs', 'generated', 'logs', 'run', 'runs', 'native-evidence'} | TEST_TREES:
        return False
    return not (path.lower().endswith('.md') or path.endswith('.log') or
                path in {'.gitignore', '.gitattributes', 'LICENSE', 'LICENSE.txt'})


def fingerprint() -> str:
    listed = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT
    ).decode().split('\0')
    digest = hashlib.sha256()
    for relative in sorted({p for p in listed if p and production_input(p)}):
        path = ROOT / relative
        digest.update(relative.encode())
        digest.update(b'\0')
        digest.update(b'\1' if os.access(path, os.X_OK) else b'\0')
        if path.is_symlink():
            digest.update(os.readlink(path).encode())
        if path.is_file():
            digest.update(b'\1')
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(65536), b''):
                    digest.update(block)
        else:
            digest.update(b'\2')
        digest.update(b'\0')
    return digest.hexdigest()


def metadata() -> str:
    value = fingerprint()
    head = subprocess.run(['git', 'rev-parse', '--verify', 'HEAD'], cwd=ROOT,
                          text=True, capture_output=True)
    identity = head.stdout.strip() if head.returncode == 0 else f'source-snapshot-sha256:{value}'
    return ('schema=bc.custom_mod_source.v1\nrepository=better-seasonal-trees\n'
            f'mod_id=better_seasonal_trees\ncommit={identity}\nproduction_fingerprint={value}\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = metadata()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result)
    else:
        print(result, end='')
