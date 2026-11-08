"""Drive the protected Android harness; changes screen state, never real Wi-Fi."""
import json
import subprocess
import time
from pathlib import Path

ADB = r"C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
BASE = "/data/local/tmp/cws-event-test"
OUT = Path(__file__).resolve().parents[2] / "build/power-audit"
OUT.mkdir(parents=True, exist_ok=True)
snapshots = {}

def command(text, check=True):
    p = subprocess.run([ADB,"shell","su","-c",text],capture_output=True,text=True,encoding="utf-8",timeout=10)
    if check and p.returncode: raise RuntimeError(p.stderr or p.stdout)
    return p

def control(value): command("echo " + value + " > " + BASE + "/state/test_control")
def status(name):
    p=command("sh " + BASE + "/module/webctl.sh status " + name + "_" + str(time.time_ns()))
    value=json.loads(p.stdout); snapshots[name]=value
    print(name, {k:value[k] for k in ("state","interactive","rssi_reads","periodic_runs","disconnect_attempts","countdown_ms","screen_channel")},flush=True)
    return value

try:
    initial=status("native_start")
    time.sleep(14)
    stable=status("native_stable")
    assert stable["disconnect_attempts"]==0 and stable["fluid_starts"]==0
    control("weak");time.sleep(1.2)
    weak=status("native_verify")
    assert weak["state"]=="VERIFY" and weak["countdown_ms"]>0
    print("SCREEN_OFF",flush=True)
    command("input keyevent 223");time.sleep(2)
    off1=status("native_off_first")
    assert not off1["interactive"] and off1["state"]=="PAUSED" and not off1["sample_scheduled"]
    for value in ["weak","strong","weak","strong"]: control(value)
    time.sleep(5)
    off2=status("native_off_later")
    assert not off2["interactive"],"The device woke during the off-only observation window; do not count this as an off-mode test"
    assert off2["rssi_reads"]==off1["rssi_reads"]
    assert off2["periodic_runs"]==off1["periodic_runs"] and off2["disconnect_attempts"]==0
    print("SCREEN_ON",flush=True)
    control("weak")
    command("input keyevent 224");time.sleep(0.8)
    wake=status("native_wake")
    assert wake["interactive"] and wake["state"]=="VERIFY" and wake["countdown_ms"]>4500
    control("strong");time.sleep(1.2)
    recovered=status("native_recovered")
    assert recovered["state"]=="IDLE" and recovered["countdown_ms"]==0 and recovered["disconnect_attempts"]==0
    command("sh " + BASE + "/module/webctl.sh threshold -85")
    threshold=status("native_setting")
    assert threshold["threshold"]==-85
    bad=command("sh " + BASE + "/module/webctl.sh threshold -999",check=False)
    assert bad.returncode!=0
    control("weak");time.sleep(1.2);control("immediate");time.sleep(0.3)
    manual=status("native_manual_guard")
    assert manual["disconnect_attempts"]==1 and manual["disconnect_accepted"]==0 and manual["state"]=="COOLDOWN"
    assert manual["fluid_starts"]==0
    assert len({v["pid"] for v in snapshots.values()})==1,"Mixed harness processes"
    print("PASS protected on-device screen lifecycle, recovery, settings and final Binder gate",flush=True)
finally:
    command("input keyevent 224",check=False)
    control("stop")
    target=OUT/"device-snapshots.json"
    if target.exists():target.rename(OUT/("device-snapshots-prior-"+str(time.time_ns())+".json"))
    target.write_text(json.dumps(snapshots,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
