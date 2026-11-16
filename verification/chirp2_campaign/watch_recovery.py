"""Poll the STT route until it recovers (or the window expires), logging each try."""
import glob
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://chirp2-test---avnivo-sensor-test-5xwrf7netq-ew.a.run.app"
WAV = sorted(glob.glob("/tmp/chirp2_samples/en-US/*.wav"))[0]
OUT = Path(__file__).resolve().parent / "recovery_watch.jsonl"


def call():
    blob = Path(WAV).read_bytes()
    b = "----w"
    head = (
        f'--{b}\r\nContent-Disposition: form-data; name="language"\r\n\r\nen-US\r\n'
        f'--{b}\r\nContent-Disposition: form-data; name="audio"; filename="s.wav"\r\n'
        f"Content-Type: audio/wav\r\n\r\n"
    ).encode()
    body = head + blob + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(
        BASE + "/api/transcribe-google", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={b}",
                 "x-forwarded-for": "203.0.113.200"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.read().decode("utf-8", "replace")[:120]
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")[:120]
    except Exception as error:
        return 0, str(error)[:120]


end = time.time() + 3600
with OUT.open("a", encoding="utf-8") as handle:
    while time.time() < end:
        status, body = call()
        handle.write(json.dumps({"t": time.strftime("%H:%M:%S"), "status": status, "body": body}) + "\n")
        handle.flush()
        if status == 200:
            print("RECOVERED", body)
            break
        time.sleep(60)
