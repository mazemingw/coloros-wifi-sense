#!/usr/bin/env python3
"""Publish compact tables from the actual machine-readable assertions."""
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build/installer-lab"
OUT = ROOT / "docs/installation-failure"
DESCRIPTIONS = {
    "normal_up": "常规安装，音量＋", "normal_down": "仅核心，移除流体云 DEX",
    "fresh_up": "首次安装，建立更新元数据", "upgrade_threshold": "保留已有 −85 阈值",
    "dir_conflict": "配置目录被普通文件占用", "dir_denied": "父目录拒绝创建",
    "dir_exists_unwritable": "已有目录不可写、缺少 threshold",
    "upgrade_readonly_config": "保留只读目录中的已有 −88 阈值",
    "threshold_is_dir": "目录存在，threshold 被目录占用",
    "existing_dir_mkdir_error": "已有目录；注入绝对路径 mkdir 失败",
    "tmp_missing_initial": "初始 TMPDIR 不存在，由安装器新建",
    "tmp_denied": "TMPDIR 父目录拒绝创建", "env_tmp_empty": "继承 TMPDIR 为空",
    "env_tmp_invalid": "继承 TMPDIR 为无效路径", "env_api_empty": "API 为空",
    "env_api_invalid": "API 非数字", "env_api_old": "API 低于 29",
    "env_ksu_empty": "KSU 为空", "abort_mid": "getevent 失败，安装中途 abort",
    "root_einval": "mkdirat('/') EINVAL 注入，已有模块目录",
    "env_mod_empty": "直接 source，MODPATH 为空", "env_mod_relative": "MODPATH 为相对路径",
    "env_mod_root": "MODPATH 为根路径",
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    reports = {phase: json.loads((BUILD / (phase + "-matrix.json")).read_text(encoding="utf-8"))
               for phase in ["before", "after"]}
    assert reports["after"]["total"] == 621 and reports["after"]["failed"] == 0
    assert reports["before"]["total"] == 621 and reports["before"]["failed"] == 135
    assert reports["before"]["unexpected_failures"] == 0
    assert reports["after"]["provenance"][0]["customize_sha256"] == hashlib.sha256((ROOT / "customize.sh").read_bytes()).hexdigest()
    for key in ["guest_sha256", "hooks_sha256"]:
        assert reports["before"]["provenance"][0][key] == reports["after"]["provenance"][0][key]
    summary = ["# 实际执行结果", "", "日期：2026-11-08。完整断言记录保存在本地 build/installer-lab/*-matrix.json。", "",
               "| 组别 | 通过 / 总数 | 通过率 |", "|---|---:|---:|"]
    for phase, name in [("before", "修复前功能断言"), ("after", "修复后功能断言及失败诊断")]:
        r = reports[phase]
        summary.append(f"| {name} | {r['passed']} / {r['total']} | {r['passed'] / r['total']:.2%} |")
        columns = [k for k in r["results"][0] if k not in ("stdout_sha256", "stderr_sha256", "errors")]
        columns += ["errors"]
        with (OUT / ("matrix-" + phase + ".tsv")).open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=columns, delimiter="\t", extrasaction="ignore")
            writer.writeheader()
            for row in r["results"]:
                writer.writerow(row | {"errors": "; ".join(row["errors"])})
    probes = json.loads((OUT / "evidence/probes.json").read_text(encoding="utf-8"))
    summary += [f"| 故障链与 shell 契约探针 | {probes['passed']} / {probes['total']} | {probes['passed'] / probes['total']:.2%} |", "",
                "探针通过表示满足其明确的观测断言，不表示无注入复现了用户设备，或已验证完整 Android 安装。首次安装的 shell-only 元数据缺失也作为边界保留。", "",
                "23 个场景均跨 9 个安装器配置、3 种 shell 执行，每行各 27 个案例。负面案例的预期是正确停止。", "",
                "| 场景 | 修复前通过 | 修复后通过 |", "|---|---:|---:|"]
    for case, name in DESCRIPTIONS.items():
        counts = []
        for phase in ["before", "after"]:
            rows = [r for r in reports[phase]["results"] if r["case"] == case]
            assert len(rows) == 27
            counts.append(f"{sum(r['passed'] for r in rows)} / {len(rows)}")
        summary.append(f"| {name} | {counts[0]} | {counts[1]} |")
    summary += ["", "基线的 135 个功能断言失败分为：已有目录仍调用失败的 mkdir（27）、根目录 errno 注入（27）、三个非法 MODPATH 场景（各 27）。这不是 135 台真实设备故障。修复后额外要求配置失败的主日志含系统错误与上下文。", "",
                "普通安装、首次安装、阈值保留、仅核心选择在修复前后均通过。测试未执行 Android SELinux 策略、完整 ksud、模块镜像事务或重启运行期功能。", "",
                "上游、依赖及脚本哈希见 evidence/provenance.json；原始 stdout/stderr/VM 命令保留在本地 build/installer-lab/。"]
    (OUT / "SUMMARY.md").write_text("\n".join(summary) + "\n", encoding="utf-8")
    provenance = {phase: r["provenance"] for phase, r in reports.items()}
    provenance["probe_runs"] = {phase: json.loads((BUILD / (phase + "-contracts/provenance.json")).read_text())
                                for phase in ["before", "after"]}
    (OUT / "evidence/provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print("Generated SUMMARY.md, two 621-row matrices and provenance.json")


if __name__ == "__main__":
    main()
