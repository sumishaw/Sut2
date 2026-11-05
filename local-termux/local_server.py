"""
Fully local Hindi voice-cloning server for Termux (runs ON the tablet, no GPU, no internet after setup).
Wraps KokoClone (Kokoro-ONNX Hindi TTS + voice conversion, Apache-2.0): https://github.com/Ashish-Patnaik/kokoclone
Endpoints (same shape the SubVoice app uses):  GET /health   POST /voice (multipart)   POST /tts/local (json)
"""
import io, json, os, subprocess, sys, tempfile, threading
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KOKO_DIR = os.path.expanduser(os.getenv("KOKO_DIR", "~/kokoclone"))
sys.path.insert(0, KOKO_DIR)
os.chdir(KOKO_DIR)                       # KokoClone downloads/loads models relative to its folder
from core.cloner import KokoClone        # noqa: E402

VOICES = os.path.expanduser("~/subvoice_voices"); os.makedirs(VOICES, exist_ok=True)
import numpy as np
import soundfile as sf
lock = threading.Lock()


def finalize(path):
    """Make the WAV playable everywhere: mono, 16-bit PCM, peak-normalised. Returns original peak."""
    d, sr = sf.read(path, dtype="float32")
    if d.ndim > 1:
        d = d.mean(axis=1)
    d = np.nan_to_num(d)
    peak = float(np.abs(d).max()) if d.size else 0.0
    print(f"output: {len(d)/sr:.2f}s sr={sr} peak={peak:.5f}", flush=True)
    if peak > 1e-4:
        d = d / peak * 0.9
    sf.write(path, d, sr, subtype="PCM_16")
    return peak
print("loading KokoClone ...", flush=True)
cloner = KokoClone()
print("ready on http://127.0.0.1:8002", flush=True)


def multipart(headers, body):
    raw = b"Content-Type: " + headers["Content-Type"].encode() + b"\r\n\r\n" + body
    msg = BytesParser(policy=default).parsebytes(raw)
    return {p.get_param("name", header="content-disposition"): p.get_payload(decode=True)
            for p in msg.iter_parts()}


class H(BaseHTTPRequestHandler):
    def _send(self, code, data=b"", ctype="application/json"):
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def do_GET(self):
        self._send(200, b'{"engine":"kokoclone-local"}')

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            if self.path == "/voice":
                f = multipart(self.headers, body)
                vid = os.path.basename(f["voice_id"].decode())
                tmp = tempfile.NamedTemporaryFile(delete=False).name
                open(tmp, "wb").write(f["file"])
                out = os.path.join(VOICES, vid + ".wav")
                r = subprocess.run(["ffmpeg", "-y", "-i", tmp, "-t", "10", "-ar", "24000", "-ac", "1", out],
                                   capture_output=True)
                os.unlink(tmp)
                return self._send(200 if r.returncode == 0 else 400, b"{}")
            if self.path == "/tts/local":
                req = json.loads(body)
                ref = os.path.join(VOICES, os.path.basename(req.get("voice_id", "")) + ".wav")
                if not os.path.exists(ref):
                    return self._send(400, b'{"error":"no reference voice"}')
                out = tempfile.NamedTemporaryFile(suffix=".wav", delete=False).name
                with lock:
                    cloner.generate(text=req["text"], lang="hi", reference_audio=ref, output_path=out)
                peak = finalize(out)
                data = open(out, "rb").read(); os.unlink(out)
                if peak <= 1e-4:
                    return self._send(500, b'{"error":"silent output"}')
                return self._send(200, data, "audio/wav")
        except Exception as e:
            return self._send(500, json.dumps({"error": str(e)}).encode())
        self._send(404)

    def log_message(self, *a): pass


ThreadingHTTPServer(("127.0.0.1", 8002), H).serve_forever()
