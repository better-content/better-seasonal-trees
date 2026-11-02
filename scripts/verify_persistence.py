"""Require both independent native-process receipts; never substitute a unit round-trip."""
import json
import sys
from pathlib import Path

attempt = Path(sys.argv[1]).resolve()
expected_parent = Path(__file__).resolve().parents[1] / 'evidence' / 'persistence'
assert attempt.parent == expected_parent, 'Not an isolated persistence attempt'
assert not list(attempt.glob('failure-*.json')), 'Native persistence failure receipt exists'
baseline = json.loads((attempt / 'baseline.json').read_text())
write = json.loads((attempt / 'success-write.json').read_text())
read = json.loads((attempt / 'success-read.json').read_text())
assert baseline['schema'] == 'bst.persistence.v1'
mode = baseline.get('mode', 'winter')
assert mode in ('winter', 'partialAutumn', 'earlyBranchDamage')
if mode == 'partialAutumn':
    assert baseline['planComplete'] is True
    assert baseline['planSize'] == len(baseline['allPlan']) == len(baseline['summerLeaves'])
    assert 0 < len(baseline['tickets']) < baseline['planSize']
    assert len(baseline['remainingPlan']) == len(baseline['partialLeaves'])
    assert len(baseline['tickets']) + len(baseline['remainingPlan']) == baseline['planSize']
for receipt, stage in ((write, 'write'), (read, 'read')):
    assert receipt['schema'] == 'bst.persistence.receipt.v1'
    assert receipt['status'] == 'PASS' and receipt['stage'] == stage
    assert receipt['attempt'] == attempt.name == baseline['attempt']
    assert receipt['worldMarker'] == baseline['worldMarker']
    assert receipt['rootIncarnation'] == baseline['rootIncarnation']
    assert receipt.get('mode', 'winter') == mode
    expected = baseline['planSize'] if mode == 'partialAutumn' and stage == 'read' else len(baseline['tickets'])
    assert receipt['tickets'] == expected > 0
assert (write['pid'], write['vmStart']) != (read['pid'], read['vmStart']), 'Same JVM is not a restart'
assert write['pid'] == baseline['writePid'] and write['vmStart'] == baseline['writeVmStart']
assert write['consumed'] == 0 and read['consumed'] == read['tickets']
assert read['serverTick'] > write['serverTick'], 'Saved world time did not advance'
assert (attempt / 'world' / 'level.dat').is_file()
print(f'Native two-process persistence PASS: {attempt.name}, mode={mode}, {read["tickets"]} opportunities')
