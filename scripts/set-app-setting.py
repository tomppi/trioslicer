#!/usr/bin/env python3
"""Set one key in the app's persisted settings, in place.

The app keeps its slicer settings as JSON blobs inside a SharedPreferences XML
file. Writing that file is how the G-code correctness rig pins a configuration
without driving the settings UI, and it is exact: the value that reaches the
engine is the value written here, with nothing typed by hand in between.

Usage: set-app-setting.py <xml> <settings-key> <json-key> <json-value>
  settings-key  one of settings-json, prusa-settings-json, orca-settings-json
  json-value    parsed as JSON, so true/false/0.2/'"text"' all work
"""
import html
import json
import re
import sys

path, settings_key, json_key, raw_value = sys.argv[1:5]
value = json.loads(raw_value)
text = open(path, encoding="utf-8").read()
pattern = re.compile(r'(<string name="%s">)(.*?)(</string>)' % re.escape(settings_key), re.S)
match = pattern.search(text)
if not match:
    sys.exit(f"no <string name={settings_key}> in {path}")
data = json.loads(html.unescape(match.group(2)))
before = data.get(json_key, "<absent>")
data[json_key] = value
encoded = html.escape(json.dumps(data), quote=True)
open(path, "w", encoding="utf-8").write(text[:match.start(2)] + encoded + text[match.end(2):])
print(f"{settings_key}.{json_key}: {before} -> {value}")
