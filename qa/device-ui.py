"""Small ADB helper for inspecting this app and selecting observed UI labels."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

serial = sys.argv[1]
action = sys.argv[2] if len(sys.argv) > 2 else "dump"
adb = ["adb", "-s", serial]

def run(*args):
    return subprocess.run(adb + list(args), check=True, capture_output=True, text=True, encoding="utf-8").stdout

run("shell", "rm", "-f", "/sdcard/OurMusic-ui.xml")
dump = run("shell", "uiautomator", "dump", "/sdcard/OurMusic-ui.xml")
if "ERROR" in dump or "dumped" not in dump:
    raise SystemExit("The screen is still changing; pause playback or inspect a screenshot before acting.")
xml = run("shell", "cat", "/sdcard/OurMusic-ui.xml")
root = ET.fromstring(xml[xml.index("<?xml"):])
nodes = [node for node in root.iter("node") if node.get("package") == "net.omusic.unreliablecode"]
if not nodes:
    raise SystemExit("Our Music is not in the foreground; open it before running this inspection.")
if action == "dump":
    for node in nodes:
        a = node.attrib
        label = a.get("text") or a.get("content-desc")
        if label:
            print(label, a.get("bounds"), a.get("clickable"), a.get("package"))
elif action in ("tap", "taplast"):
    import re
    wanted = sys.argv[3]
    matches = [n for n in nodes if wanted in (n.get("text"), n.get("content-desc"))]
    if not matches:
        raise SystemExit("Observed label not found: " + wanted)
    clickable = [n for n in matches if n.get("clickable") == "true"]
    target = (clickable or matches)[-1 if action == "taplast" else (int(sys.argv[4]) if len(sys.argv) > 4 else 0)]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
    print("Tap observed", wanted, target.get("bounds"), target.get("package"))
    run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
else:
    raise SystemExit("Actions: dump, tap <exact label> [index], taplast <exact label>")
