"""Frontend / backend / live-endpoint / official-doc language consistency."""
import json, re, sys, urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "translator-api"))
import chirp2_languages as registry

BASE = "https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app"

def ui_matrix():
    src = (REPO / "avnivo-v11-49-chirp2-multilingual.html").read_text(encoding="utf-8")
    block = src.split("window.CHIRP2_MATRIX=[", 1)[1].split("];", 1)[0]
    return re.findall(r'\["([A-Za-z0-9-]+)","([^"]+)"\]', block)

def live():
    with urllib.request.urlopen(BASE + "/api/chirp2-languages", timeout=60) as r:
        data = json.load(r)
    return data

def main():
    ui = ui_matrix()
    ui_codes = [c for c, _ in ui]
    reg = list(registry.CHIRP2_LOCALES)
    lv = live()
    lv_codes = lv.get("languages") if isinstance(lv.get("languages"), list) else lv.get("locales")
    if lv_codes and isinstance(lv_codes[0], dict):
        lv_codes = [d.get("code") or d.get("locale") for d in lv_codes]
    report = {
        "ui_count": len(ui_codes), "ui_unique": len(set(ui_codes)),
        "registry_count": len(reg), "registry_unique": len(set(reg)),
        "live_count": len(lv_codes or []), "live_unique": len(set(lv_codes or [])),
        "ui_equals_registry": set(ui_codes) == set(reg),
        "ui_equals_live": set(ui_codes) == set(lv_codes or []),
        "registry_equals_live": set(reg) == set(lv_codes or []),
        "ui_minus_registry": sorted(set(ui_codes) - set(reg)),
        "registry_minus_ui": sorted(set(reg) - set(ui_codes)),
        "live_minus_ui": sorted(set(lv_codes or []) - set(ui_codes)),
        "ui_minus_live": sorted(set(ui_codes) - set(lv_codes or [])),
        "live_model": lv.get("model"),
        "live_region": lv.get("region") or lv.get("location"),
        "live_configured": lv.get("configured"),
    }
    print(json.dumps(report, indent=1))
    (Path(__file__).resolve().parent / "consistency.json").write_text(json.dumps(report, indent=1), encoding="utf-8")

if __name__ == "__main__":
    main()
