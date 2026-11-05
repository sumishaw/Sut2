"""
SubVoice TTS server.
  ENGINES=xtts       -> serves POST /tts/xtts         (Coqui XTTS-v2, built-in male/female voices)
  ENGINES=chatterbox -> serves POST /tts/chatterbox   (Chatterbox Multilingual, Hindi voice cloning)
  ENGINES=xtts,chatterbox -> both (only if their pip dependencies coexist; otherwise run two venvs)
Always available: POST /voice (upload reference clip), GET /health
"""
import io, os, subprocess, tempfile, threading
import numpy as np
import soundfile as sf
import torch
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

ENGINES = os.getenv("ENGINES", "xtts,chatterbox").split(",")
DEV = "cuda" if torch.cuda.is_available() else "cpu"
VOICES = os.path.abspath("voices"); os.makedirs(VOICES, exist_ok=True)
XTTS_SPEAKERS = {"female": "Claribel Dervla", "male": "Damien Black"}

app = FastAPI()
lock = threading.Lock()
_xtts = None
_cb = None


class Req(BaseModel):
    text: str
    language: str = "hi"
    gender: str = "female"
    voice_id: str = ""


def voice_path(vid: str):
    if not vid:
        return None
    p = os.path.join(VOICES, os.path.basename(vid) + ".wav")
    return p if os.path.exists(p) else None


def wav_response(wav, sr):
    if hasattr(wav, "detach"):
        wav = wav.detach().cpu().numpy()
    arr = np.asarray(wav, dtype="float32").squeeze()
    buf = io.BytesIO()
    sf.write(buf, arr, sr, format="WAV")
    return Response(buf.getvalue(), media_type="audio/wav")


@app.get("/health")
def health():
    return {"engines": ENGINES, "device": DEV}


@app.post("/voice")
async def upload_voice(voice_id: str = Form(...), file: UploadFile = File(...)):
    raw = await file.read()
    with tempfile.NamedTemporaryFile(delete=False) as t:
        t.write(raw)
    out = os.path.join(VOICES, os.path.basename(voice_id) + ".wav")
    # normalise to mono 24 kHz, max 20 s (needs ffmpeg installed)
    r = subprocess.run(["ffmpeg", "-y", "-i", t.name, "-t", "20", "-ar", "24000", "-ac", "1", out],
                       capture_output=True)
    os.unlink(t.name)
    if r.returncode != 0:
        raise HTTPException(400, "could not decode audio: " + r.stderr.decode()[-200:])
    return {"voice_id": voice_id}


def load_xtts():
    global _xtts
    if _xtts is None:
        from TTS.api import TTS
        _xtts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to(DEV)


def load_cb():
    global _cb
    if _cb is None:
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS
        _cb = ChatterboxMultilingualTTS.from_pretrained(device=DEV)


@app.on_event("startup")
def warm_up():
    if "xtts" in ENGINES: load_xtts()
    if "chatterbox" in ENGINES: load_cb()


@app.post("/tts/xtts")
def tts_xtts(r: Req):
    if "xtts" not in ENGINES:
        raise HTTPException(404, "xtts not enabled on this server")
    with lock:
        load_xtts()
        speaker = XTTS_SPEAKERS.get(r.gender, XTTS_SPEAKERS["female"])
        wav = _xtts.tts(text=r.text, language=r.language, speaker=speaker)
    return wav_response(wav, 24000)


@app.post("/tts/chatterbox")
def tts_chatterbox(r: Req):
    if "chatterbox" not in ENGINES:
        raise HTTPException(404, "chatterbox not enabled on this server")
    with lock:
        load_cb()
        wav = _cb.generate(r.text, language_id="hi",            # Hindi only
                           audio_prompt_path=voice_path(r.voice_id))
        sr = _cb.sr
    return wav_response(wav, sr)
