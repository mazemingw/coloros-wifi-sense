#!/usr/bin/env python3
"""Assert observable installation results, not just shell exit codes."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build/installer-lab"
SUCCESS = {
    "normal_up", "normal_down", "fresh_up", "upgrade_threshold",
    "upgrade_readonly_config", "existing_dir_mkdir_error", "tmp_missing_initial",
    "env_tmp_empty", "env_tmp_invalid", "env_ksu_empty", "root_einval",
}
KNOWN_BASELINE_FAILURES = {
    "existing_dir_mkdir_error", "root_einval", "env_mod_empty", "env_mod_relative", "env_mod_root",
}


def verify(row, stdout, stderr, phase):
    errors = []
    case = row["case"]
    succeeds = case in SUCCESS
    wanted = "0" if succeeds else "1"
    if row["rc"] != wanted:
        errors.append(f"exit {row['rc']}, expected {wanted}")
    if row["old_module"] != ("no" if case == "fresh_up" else "yes"):
        errors.append("pre-existing module was modified")
    if succeeds:
        expected = {"upgrade_threshold": "-85", "upgrade_readonly_config": "-88"}.get(case, "-80")
        if row["threshold"] != expected:
            errors.append(f"threshold {row['threshold']}, expected {expected}")
        fluid = "0" if case == "normal_down" else "1"
        if row["fluid"] != fluid:
            errors.append("volume-key choice not preserved")
        if row["fluid_dex"] != ("no" if fluid == "0" else "yes"):
            errors.append("optional DEX payload incorrect")
        if row["update"] != "yes" or row["staging"] != "yes":
            errors.append("installer update metadata/staging missing")
        if row["tmp"] != "no":
            errors.append("temporary directory not cleaned")
        if case in ("normal_up", "normal_down", "fresh_up", "tmp_missing_initial"):
            if row["dir_mode"] != "700" or row["file_mode"] != "600":
                errors.append("new configuration permissions incorrect")
    elif row["threshold"] != "missing":
        errors.append("configuration was written in a rejected environment")
    if phase == "after" and case in ("dir_conflict", "dir_denied", "dir_exists_unwritable", "threshold_is_dir"):
        for text in ["配置路径", "MODPATH=", "TMPDIR=", "身份", "系统错误"]:
            if text not in stdout:
                errors.append(f"missing stdout diagnostic: {text}")
    if phase == "after" and case.startswith("env_mod_") and "MODPATH" not in stdout:
        errors.append("missing invalid-MODPATH diagnostic")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["before", "after"], required=True)
    parser.add_argument("--labels", required=True, help="Comma-separated completed VM output labels")
    parser.add_argument("--expected-cases", type=int, default=621)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    results = []
    provenances = []
    seen = set()
    for label in args.labels.split(","):
        base = BUILD / label
        if not base.resolve().is_relative_to(BUILD.resolve()):
            parser.error("Invalid result label")
        provenances.append(json.loads((base / "provenance.json").read_text()))
        with (base / "results/matrix.tsv").open(encoding="utf-8", newline="") as f:
            rows = list(csv.DictReader(f, delimiter="\t"))
        for i, row in enumerate(rows, 1):
            key = (row["installer"], row["shell"], row["case"])
            if key in seen:
                raise ValueError(f"Duplicate case: {key}")
            seen.add(key)
            folder = base / "results" / (f"{i:03d}-" + "-".join(key))
            stdout = (folder / "stdout.log").read_text(encoding="utf-8")
            stderr = (folder / "stderr.log").read_text(encoding="utf-8")
            errors = verify(row, stdout, stderr, args.phase)
            results.append(row | {"passed": not errors, "errors": errors,
                                  "stdout_sha256": hashlib.sha256(stdout.encode()).hexdigest(),
                                  "stderr_sha256": hashlib.sha256(stderr.encode()).hexdigest(),
                                  "log_directory": str(folder.relative_to(ROOT)).replace("\\", "/")})
    for key in ["phase", "customize_sha256", "guest_sha256", "hooks_sha256"]:
        if len({p[key] for p in provenances}) != 1:
            raise ValueError(f"Mixed provenance: {key}")
    failures = [r for r in results if not r["passed"]]
    unexpected = [r for r in failures if not (args.phase == "before" and r["case"] in KNOWN_BASELINE_FAILURES)]
    report = {"phase": args.phase, "total": len(results), "passed": len(results) - len(failures),
              "failed": len(failures), "unexpected_failures": len(unexpected),
              "failure_categories": dict(Counter(r["case"] for r in failures)),
              "provenance": provenances, "results": results}
    output = args.output or BUILD / (args.phase + "-matrix.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in ("results", "provenance")}, ensure_ascii=False, indent=2))
    for row in unexpected[:10]:
        print(row["installer"], row["shell"], row["case"], row["errors"])
    if len(results) != args.expected_cases or unexpected or (args.phase == "after" and failures):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
