#!/usr/bin/env python3
"""Build a Modrinth modpack (.mrpack) that Prism Launcher and the Modrinth App import in one click.

The pack pins Minecraft + Fabric Loader (versions read from mod/gradle.properties), includes Fabric API
(looked up through the Modrinth API at build time, so the CDN url/hashes/size are always real) and
the gbalink jar from the GitHub release download url of the tag being released.

    python3 scripts/make_mrpack.py --jar mod/build/libs/gbalink-0.1.0.jar --repo owner/name --tag v0.1.0 \
        --out dist/gbalink-0.1.0.mrpack

Stdlib only. Needs network access to api.modrinth.com (unless --fabric-api-json is given, which takes a
saved response of `GET /v2/project/fabric-api/version?...` - handy for tests).
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.modrinth.com/v2"
ALLOWED_HOSTS = ("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")  # mrpack spec


def props(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fabric_api_versions(mc, json_file):
    if json_file:
        with open(json_file, encoding="utf-8") as f:
            return json.load(f)
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    req = urllib.request.Request(f"{API}/project/fabric-api/version?{q}",
                                 headers={"User-Agent": "gbalink-mrpack-builder (github.com release script)"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def fabric_api_file(version, mc, json_file):
    for v in fabric_api_versions(mc, json_file):
        if v.get("version_number") == version:
            files = v["files"]
            f = next((x for x in files if x.get("primary")), files[0])
            host = urllib.parse.urlparse(f["url"]).hostname
            if host not in ALLOWED_HOSTS:
                sys.exit(f"download host not allowed in an mrpack: {host}")
            return {
                "path": "mods/" + f["filename"],
                "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
                "env": {"client": "required", "server": "required"},
                "downloads": [f["url"]],
                "fileSize": f["size"],
            }
    sys.exit(f"Fabric API {version} for Minecraft {mc} not found on Modrinth")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True, help="built gbalink jar")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), help="GitHub owner/name")
    ap.add_argument("--tag", required=True, help="release tag, e.g. v0.1.0")
    ap.add_argument("--out", required=True)
    ap.add_argument("--fabric-api-json", help="use a saved Modrinth version listing instead of the network")
    a = ap.parse_args()
    if not a.repo:
        sys.exit("--repo owner/name (or GITHUB_REPOSITORY) is required")

    p = props(os.path.join(ROOT, "mod", "gradle.properties"))
    mc, loader, fapi, version = p["minecraft_version"], p["loader_version"], p["fabric_api_version"], p["version"]
    jar_name = os.path.basename(a.jar)
    jar_url = f"https://github.com/{a.repo}/releases/download/{urllib.parse.quote(a.tag)}/{urllib.parse.quote(jar_name)}"

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "GBA Link",
        "summary": "Play Game Boy Advance games in Minecraft and link up with friends. Fabric, Minecraft " + mc + ".",
        "files": [
            fabric_api_file(fapi, mc, a.fabric_api_json),
            {
                "path": "mods/" + jar_name,
                "hashes": {"sha1": digest(a.jar, "sha1"), "sha512": digest(a.jar, "sha512")},
                "env": {"client": "required", "server": "required"},
                "downloads": [jar_url],
                "fileSize": os.path.getsize(a.jar),
            },
        ],
        "dependencies": {"minecraft": mc, "fabric-loader": loader},
    }
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2) + "\n")
    print(f"wrote {a.out}: Minecraft {mc}, Fabric Loader {loader}, Fabric API {fapi}, gbalink {version}")


if __name__ == "__main__":
    main()
