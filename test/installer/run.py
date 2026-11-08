#!/usr/bin/env python3
"""Boot an unshared, diskless Linux VM; never execute installers on the host."""
import argparse
import base64
import gzip
import hashlib
import io
import json
import re
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
BUILD = ROOT / "build/installer-lab"
CACHE = BUILD / "cache"
ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.24/"
RESOURCES = {
    "alpine-minirootfs-3.24.4-x86_64.tar.gz": (
        ALPINE + "releases/x86_64/alpine-minirootfs-3.24.4-x86_64.tar.gz",
        "cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012"),
    "vmlinuz-virt": (
        ALPINE + "releases/x86_64/netboot-3.24.4/vmlinuz-virt",
        "1e8ac7aebfab3f0fcb443eae94ad320efbbda51be91b928c32ca1740b44ebe9d"),
    "dash-0.5.13.1-r2.apk": (
        ALPINE + "main/x86_64/dash-0.5.13.1-r2.apk",
        "d63e8453cd38a71269a8c5507c5eff936dfff19ed732839f3952e8e37aae5610"),
    "kernelsu-busybox": (
        "https://raw.githubusercontent.com/tiann/KernelSU/730f644615969cfd4a84b45d7f62a667b7b95a50/userspace/ksud/bin/x86_64/busybox",
        "060844b0769f7a50262854af027c4d6076a212d160a51309b53057cfb7122900"),
}


def fetch(url, path, digest=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with urllib.request.urlopen(url, timeout=60) as r:
            path.write_bytes(r.read())
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest and actual != digest:
        raise ValueError(f"Checksum mismatch: {path}")
    return path


def upstreams():
    manifest = json.loads((HERE / "upstreams.json").read_text(encoding="utf-8"))
    installers = {}
    for entry in manifest:
        for f in entry["files"]:
            if not (f["path"].endswith("/installer.sh") or
                    f["path"] == "scripts/util_functions.sh"):
                continue
            url = f"https://raw.githubusercontent.com/{entry['repo']}/{entry['commit']}/{f['path']}"
            dest = CACHE / "upstream" / f["local"]
            fetch(url, dest, f["sha256"])
            name = entry["label"]
            if "ksud_magic" in f["path"]:
                name += "-magic"
            if "ksud_overlayfs" in f["path"]:
                name += "-overlayfs"
            installers[name] = dest.read_bytes()
    return installers


def tar_entries(path):
    entries = {}
    with tarfile.open(path, "r:gz", ignore_zeros=True) as t:
        for m in t:
            name = m.name.removeprefix("./").rstrip("/")
            if not name or name.startswith("."):
                continue
            if m.isdir():
                entries[name] = (stat.S_IFDIR | m.mode, b"")
            elif m.issym():
                entries[name] = (stat.S_IFLNK | 0o777, m.linkname.encode())
            elif m.isfile() or m.islnk():
                entries[name] = (stat.S_IFREG | m.mode, t.extractfile(m).read())
    return entries


def cpio(entries):
    out = io.BytesIO()
    inode = 1
    for name, (mode, data) in entries.items():
        encoded = name.encode() + b"\0"
        fields = [inode, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(encoded), 0]
        out.write(b"070701" + b"".join(f"{n:08x}".encode() for n in fields))
        out.write(encoded)
        out.write(b"\0" * (-out.tell() % 4))
        out.write(data)
        out.write(b"\0" * (-out.tell() % 4))
        inode += 1
    encoded = b"TRAILER!!!\0"
    fields = [0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, len(encoded), 0]
    out.write(b"070701" + b"".join(f"{n:08x}".encode() for n in fields) + encoded)
    out.write(b"\0" * (-out.tell() % 512))
    return gzip.compress(out.getvalue(), mtime=0)


def add_file(entries, path, data, executable=False):
    for parent in reversed(PurePosixPath(path).parents):
        if str(parent) != ".":
            entries.setdefault(str(parent), (stat.S_IFDIR | 0o755, b""))
    entries[path] = (stat.S_IFREG | (0o755 if executable else 0o644), data)


def module_zip(source, files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, source if name == "customize.sh" else data)
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["before", "after"], required=True)
    parser.add_argument("--qemu", default=shutil.which("qemu-system-x86_64"))
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--smoke", action="store_true", help="Check VM/harness setup with four cases")
    parser.add_argument("--probe", action="store_true", help="Trace real mkdir syscalls with explicit EINVAL injection")
    parser.add_argument("--accel", default="tcg", choices=["tcg", "whpx", "kvm"])
    parser.add_argument("--profiles", help="Comma-separated upstream labels; defaults to all")
    parser.add_argument("--label", help="Separate output directory for parallel batches")
    parser.add_argument("--module-zip", type=Path, help="Current built ZIP for the after phase; defaults to module.prop version")
    args = parser.parse_args()
    qemu = args.qemu or str(CACHE / "qemu/qemu-system-x86_64.exe")
    if not Path(qemu).is_file():
        parser.error("Supply --qemu with a QEMU system x86_64 executable")
    label = args.label or args.phase + ("-probe" if args.probe else "-smoke" if args.smoke else "")
    if not re.fullmatch(r"[a-zA-Z0-9._-]+", label) or label in (".", ".."):
        parser.error("Unsafe output label")
    output = BUILD / label
    output.mkdir(parents=True, exist_ok=True)
    for name, (url, digest) in RESOURCES.items():
        fetch(url, CACHE / name, digest)
    entries = tar_entries(CACHE / "alpine-minirootfs-3.24.4-x86_64.tar.gz")
    entries.update(tar_entries(CACHE / "dash-0.5.13.1-r2.apk"))
    probe_resources = json.loads((HERE / "probe-resources.json").read_text())
    for name, (url, digest) in probe_resources.items():
        entries.update(tar_entries(fetch(url, CACHE / name, digest)))
    # Only copy the trace program and its shared libraries to each test root.
    for path, data in list(entries.items()):
        if path.startswith("usr/lib/") and (stat.S_ISREG(data[0]) or stat.S_ISLNK(data[0])):
            add_file(entries, "lab/probe-libs/" + path.rsplit("/", 1)[1], data[1])
            entries["lab/probe-libs/" + path.rsplit("/", 1)[1]] = data
    add_file(entries, "lab/ksu/busybox", (CACHE / "kernelsu-busybox").read_bytes(), True)
    installers = upstreams()
    if args.profiles:
        selected = args.profiles.split(",")
        unknown = set(selected) - installers.keys()
        if unknown:
            parser.error(f"Unknown profiles: {sorted(unknown)}")
        installers = {name: installers[name] for name in selected}
    for name, data in installers.items():
        add_file(entries, f"lab/upstream/{name}.sh", data)
    release_hash = "245f924bf0476430c242a9725a01e7ada81fb0c1812e32e2000cdc65197c0b54"
    release = ROOT / "build/coloros-wifi-sense-v1.1.0.zip"
    if not release.exists() or hashlib.sha256(release.read_bytes()).hexdigest() != release_hash:
        release = fetch(
            "https://github.com/mazemingw/coloros-wifi-sense/releases/download/v1.1.0/coloros-wifi-sense-v1.1.0.zip",
            CACHE / "coloros-wifi-sense-v1.1.0.zip", release_hash)
    with zipfile.ZipFile(release) as z:
        files = {n: z.read(n) for n in z.namelist()}
    original = subprocess.check_output(["git", "show", "v1.1.0:customize.sh"], cwd=ROOT)
    current_source = (ROOT / "customize.sh").read_bytes().replace(b"\r\n", b"\n")
    version = re.search(r"^version=(.+)$", (ROOT / "module.prop").read_text(encoding="utf-8"), re.M)[1].strip()
    candidate = args.module_zip or ROOT / f"build/coloros-wifi-sense-{version}.zip"
    candidate_hash = None
    if args.phase == "after":
        if not candidate.is_file():
            parser.error(f"Build the current module first, or supply --module-zip: {candidate}")
        candidate_data = candidate.read_bytes()
        candidate_hash = hashlib.sha256(candidate_data).hexdigest()
        with zipfile.ZipFile(io.BytesIO(candidate_data)) as z:
            if z.read("customize.sh").replace(b"\r\n", b"\n") != current_source:
                parser.error("Candidate ZIP customize.sh differs from the working tree; rebuild first")
        add_file(entries, "lab/after.zip", candidate_data)
    add_file(entries, "lab/before.zip", module_zip(original, files))
    for name in ["guest.sh", "hooks.sh"]:
        add_file(entries, f"lab/{name}", (HERE / name).read_bytes().replace(b"\r\n", b"\n"), True)
    add_file(entries, "lab/phase", args.phase.encode())
    provenance = {
        "phase": args.phase, "profiles": list(installers),
        "customize_sha256": hashlib.sha256(original if args.phase == "before" else (ROOT / "customize.sh").read_bytes()).hexdigest(),
        "guest_sha256": hashlib.sha256((HERE / "guest.sh").read_bytes()).hexdigest(),
        "hooks_sha256": hashlib.sha256((HERE / "hooks.sh").read_bytes()).hexdigest(),
        "baseline_zip_sha256": release_hash,
        "candidate_zip_sha256": candidate_hash,
        "resources": RESOURCES | probe_resources,
    }
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    if args.smoke:
        add_file(entries, "lab/smoke", b"1")
    if args.probe:
        add_file(entries, "lab/probe", b"1")
    init = b"""#!/bin/sh
export PATH=/sbin:/bin:/usr/sbin:/usr/bin
mount -t devtmpfs devtmpfs /dev
mount -t proc proc /proc
mount -t sysfs sysfs /sys
echo 'WIFI_LAB_BOOTED'
/bin/sh /lab/guest.sh
echo 'WIFI_LAB_RESULTS_BEGIN'
tar -czf - -C /results . | base64
echo 'WIFI_LAB_RESULTS_END'
poweroff -f
"""
    add_file(entries, "init", init, True)
    image = output / "initramfs.cpio.gz"
    image.write_bytes(cpio(entries))
    cmd = [str(Path(qemu).resolve()), "-machine", "pc", "-accel", args.accel, "-cpu", "max" if args.accel == "tcg" else "host",
           "-m", "384", "-smp", "1", "-display", "none", "-monitor", "none",
           "-serial", "stdio", "-nic", "none", "-no-reboot", "-kernel", str(CACHE / "vmlinuz-virt"),
           "-initrd", str(image), "-append",
           "console=ttyS0 quiet panic=-1 rdinit=/init wifisense-installer-lab=1"]
    (output / "vm-command.json").write_text(json.dumps(cmd, indent=2), encoding="utf-8")
    print(f"Booting isolated Linux VM ({args.phase}); {len(installers)} installers", flush=True)
    with (output / "serial.log").open("wb") as log:
        try:
            result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        except subprocess.TimeoutExpired:
            raise SystemExit(f"VM timeout; inspect {output / 'serial.log'}")
    serial = (output / "serial.log").read_text(encoding="utf-8", errors="replace").replace("\r", "")
    if "WIFI_LAB_RESULTS_END" not in serial:
        print(serial[-6000:])
        raise SystemExit(f"VM did not finish (QEMU exit {result.returncode})")
    encoded = serial.split("WIFI_LAB_RESULTS_BEGIN\n", 1)[1].split("WIFI_LAB_RESULTS_END", 1)[0]
    archive = base64.b64decode(encoded)
    result_dir = output / "results"
    if result_dir.exists():
        if not result_dir.resolve().is_relative_to(BUILD.resolve()) or result_dir.name != "results":
            raise ValueError("Unsafe result directory")
        shutil.rmtree(result_dir)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as t:
        t.extractall(output / "results", filter="data")
    rows = (output / "results/matrix.tsv").read_text(encoding="utf-8").splitlines()
    print(f"Collected {len(rows) - 1} case results")
    print(f"Logs: {output / 'results'}")


if __name__ == "__main__":
    main()
