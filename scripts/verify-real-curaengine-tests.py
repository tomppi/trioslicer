#!/usr/bin/env python3
"""Fails unless the real-CuraEngine tests really ran.

A bare grep for a class name also matches a testsuite whose every case carries
<skipped/>, which is the shape a real-engine test takes when it self-skips: the
host binary is unreachable, the suite reports itself as skipped, and the build
stays green with the strongest proof in it quietly gone. The JUnit XML is parsed
instead, and every class has to report tests of its own with no failures, no
errors and no skips.

Usage: python3 scripts/verify-real-curaengine-tests.py <test-results-dir>
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED_CLASSES = (
    "SmartInfillCuraEngineIntegrationTest",
    "SmartInfillModifierShellIntegrationTest",
    "SmartInfillCuraContractTest",
    "SmartInfillPatternCommandTest",
)


def suites(root):
    """Every testsuite element Gradle wrote under [root], with its file."""
    paths = sorted(Path(root).glob("**/*.xml"))
    if not paths:
        raise SystemExit("no test-result XML under %s; did the test task run?" % root)
    for path in paths:
        try:
            tree = ET.parse(path)
        except ET.ParseError as error:
            raise SystemExit("%s is not readable test XML: %s" % (path, error))
        for suite in tree.iter("testsuite"):
            yield path, suite


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: verify-real-curaengine-tests.py <test-results-dir>")
    root = Path(argv[1])
    if not root.is_dir():
        raise SystemExit("no test results at %s" % root)

    found = {name: [] for name in REQUIRED_CLASSES}
    for path, suite in suites(root):
        name = suite.get("name", "")
        for required in REQUIRED_CLASSES:
            if name == required or name.endswith("." + required):
                found[required].append((path, suite))

    absent = [name for name in REQUIRED_CLASSES if not found[name]]
    if absent:
        raise SystemExit("these real-CuraEngine test classes produced no results: " + ", ".join(absent))

    problems = []
    for name in REQUIRED_CLASSES:
        for path, suite in found[name]:
            tests = int(suite.get("tests", "0") or "0")
            failures = int(suite.get("failures", "0") or "0")
            errors = int(suite.get("errors", "0") or "0")
            # The suite attribute is what Gradle writes, but a <skipped/> case
            # with no matching count must not slip through either.
            skipped = max(int(suite.get("skipped", "0") or "0"), sum(1 for _ in suite.iter("skipped")))
            if tests < 1 or failures or errors or skipped:
                problems.append(
                    "%s in %s: tests=%d failures=%d errors=%d skipped=%d"
                    % (name, path.name, tests, failures, errors, skipped)
                )
            else:
                print("ran %s: %d tests, 0 skipped, 0 failed (%s)" % (name, tests, path.name))

    if problems:
        raise SystemExit("real-CuraEngine tests did not run cleanly:\n  " + "\n  ".join(problems))
    print("all %d real-CuraEngine test classes ran with nothing skipped" % len(REQUIRED_CLASSES))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
