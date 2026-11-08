#!/usr/bin/env python3
"""Verify syscall-injection and sourced-shell contract probes; export evidence."""
import csv
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build/installer-lab"
OUT = ROOT / "docs/installation-failure/evidence"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    for phase in ["before", "after"]:
        base = BUILD / (phase + "-contracts/results")
        rows = list(csv.DictReader((base / "matrix.tsv").open(encoding="utf-8"), delimiter="\t"))
        assert len(rows) == 6
        for i, row in enumerate(rows, 1):
            case = row["case"]
            folder = base / f"{i:03d}-fina-ksu-ash-{case}"
            expected = "1" if case == "contract_abort" or phase == "before" and case.startswith("root_einval") else "0"
            assert row["rc"] == expected, (phase, case, row)
            if case.startswith("root_einval"):
                trace = (folder / "strace-mkdir.log").read_text()
                assert '"/"' in trace and "EINVAL" in trace and "(INJECTED)" in trace
                assert '"/dev/tmp"' in trace
                if phase == "before":
                    assert '"/data/adb/coloros_wifi_sense"' in trace
                else:
                    assert 'mkdirat(AT_FDCWD, "coloros_wifi_sense"' in trace
            if case == "root_einval":
                stdout = (folder / "stdout.log").read_text(encoding="utf-8")
                stderr = (folder / "stderr.log").read_text(encoding="utf-8")
                assert "已选择：核心及流体云通知" in stdout
                assert "sh: cd: line 360:" in stderr
                assert stderr.count("Archive:") == 3
                if phase == "before":
                    assert "无法创建阈值配置目录" in stdout
                    ui = stdout + "- Error: Failed to install module script\nError code: 1.\n " + stderr + "Error: Failed to install module script Please save and check the log.\n"
                    (OUT / "before-modeled-finasu-ui.log").write_text(ui, encoding="utf-8")
                for filename in ["stdout.log", "stderr.log", "strace-mkdir.log"]:
                    shutil.copyfile(folder / filename, OUT / (phase + "-injected-" + filename))
            if case == "root_einval_merged":
                text = (folder / "combined.log").read_text(encoding="utf-8")
                assert text.index("Invalid argument") < text.index("Archive:") < text.index("已选择")
                if phase == "before":
                    assert text.index("已选择") < text.index("无法创建阈值配置目录")
                    assert text.rfind("Archive:") < text.rfind("无法创建阈值配置目录")
                shutil.copyfile(folder / "combined.log", OUT / (phase + "-chronological.log"))
            if case == "root_einval_fresh" and phase == "after":
                assert row["update"] == "no"  # shell only; ksud Rust post-processing is not executed
                shutil.copyfile(folder / "stderr.log", OUT / "after-fresh-shell-only.stderr.log")
            if case == "contract_success":
                assert (folder / "context.before").read_bytes() == (folder / "context.after").read_bytes()
                assert (folder / "continued").exists()
                shutil.copyfile(folder / "context.after", OUT / (phase + "-caller-context.txt"))
            if case == "contract_abort":
                assert not (folder / "continued").exists()
                assert row["tmp"] == "no"
            if case == "contract_abort_subshell":
                assert (folder / "continued").exists()
                assert row["tmp"] == "no"
            results.append({"phase": phase, "case": case, "passed": True, "observed": row})
    report = {"passed": len(results), "total": 12, "results": results,
              "scope": "Shell/errno/UI-rendering model only; no Android ksud, manager APK or device kernel executed"}
    (OUT / "probes.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("PASS: 12/12 probe assertions; fresh-install ksud post-processing remains outside shell-only scope")


if __name__ == "__main__":
    main()
