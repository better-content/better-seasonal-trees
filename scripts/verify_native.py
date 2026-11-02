"""Native logs must report a completed nonempty suite without failures."""
import re, sys
from pathlib import Path
p = Path(sys.argv[1]); text = p.read_text(errors='replace')
finished = re.findall(r'(\d+) (?:tests done|GAME TESTS COMPLETE)', text, re.I)
assert finished and int(finished[-1]) > 0, 'No completed nonempty native GameTest suite'
if len(sys.argv) > 2:
    assert int(finished[-1]) == int(sys.argv[2]) > 0, 'Native suite count differs from required fixture inventory'
assert re.search(r'All (?:\d+ )?required tests passed', text), 'Missing required-test success summary'
assert not re.search(r'\b[1-9]\d* (?:required |optional )?tests? failed\b', text, re.I), 'Native test failure'
assert not re.search(r'Mixin apply.*failed|InjectionError|InvalidMixinException|Exception in server tick loop', text), 'Runtime integration failed'
print(f'Native suite PASS: {finished[-1]} tests; {p}')
