"""Fail closed on unusable shipping artifacts; does not claim gameplay qualification."""
import sys, zipfile, hashlib
from pathlib import Path
from source_identity import metadata
p = Path(sys.argv[1])
with zipfile.ZipFile(p) as jar:
    names = set(jar.namelist())
    required = {'META-INF/mods.toml', 'META-INF/better-content-source.properties',
                'better_seasonal_trees.mixins.json', 'better_seasonal_trees.refmap.json',
                'com/bettercontent/seasonaltrees/BetterSeasonalTrees.class'}
    assert required <= names, f'Missing runtime files: {required - names}'
    assert not any(any(part in n for part in ('/gametest/', '/nativefixture/', '/harness/', '/persistence/', 'gameteststructures/')) for n in names), 'Fixture code leaked into runtime JAR'
    assert jar.read('META-INF/better-content-source.properties').decode() == metadata(), 'Source identity differs from current pack-compatible fingerprint'
    assert jar.read('better_seasonal_trees.refmap.json').strip() not in (b'{}', b''), 'Empty refmap'
print(f'Runtime packaging PASS: {p.name} sha256={hashlib.sha256(p.read_bytes()).hexdigest()}')
