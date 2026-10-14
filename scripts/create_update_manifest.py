import argparse
import datetime
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--output", type=Path, default=Path("update.json"))
args = parser.parse_args()
properties = dict(line.split("=", 1) for line in Path("version.properties").read_text().splitlines() if "=" in line)
version = properties["VERSION_NAME"].strip()
code = int(properties["VERSION_CODE"])
base = "https://github.com/unreliablecode/Our-Music-Mobile/releases"
manifest = {
    "schemaVersion": 1,
    "packageName": "net.omusic.unreliablecode",
    "versionName": version,
    "versionCode": code,
    "apkUrl": f"{base}/download/v{version}/OurMusic.apk",
    "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest(),
    "apkBytes": args.apk.stat().st_size,
    "minSdk": 23,
    "publishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "changelog": Path("CHANGELOG.md").read_text(encoding="utf-8").strip(),
    "releaseUrl": f"{base}/tag/v{version}",
}
args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
