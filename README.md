# SubVoice (tablet-only version)
Reads YouTube / browser subtitles and speaks them aloud. No PC or GPU of your own needed.

## Engines
| Engine | Runs on | Needs | Quality |
|---|---|---|---|
| `device` | tablet, fully offline | Hindi voice data (Google Speech Services) | Clear male/female Hindi, **no cloning** |
| `chatterbox` | free cloud GPU (Colab/Kaggle) via the notebook | internet | Hindi **voice cloning** |
| `xtts` | same notebook, optional | internet | built-in male/female voices |

If the server is unreachable the app falls back to the `device` voice automatically.

## 1. Build the APK on GitHub
Push this folder to a repo's `main` branch -> Actions -> *Build APK* -> download `SubVoice-debug-apk`.

## 2. Offline use (works immediately)
Install Hindi voice data (button in app), pick a female and a male voice, enable the accessibility service, play a video with CC on.

## 3. Cloned voice
1. Open `SubVoice_GPU_Server.ipynb` in Google Colab (or Kaggle) on the tablet browser, set GPU, run the cells in order.
2. Paste the printed `trycloudflare.com` link in the app (*Chatterbox server URL*).
3. Tap *Choose reference voice* (10-20 s clean Hindi speech), set engine to `chatterbox`.
Keep the notebook tab open. Each new session gives a new link; the app re-uploads your voice automatically.

## 4. Fully local cloned voice (experimental, no internet/GPU) - engine `local`
Uses KokoClone (Kokoro-ONNX Hindi + voice conversion, from Hugging Face / GitHub) inside Termux on the tablet.
1. Install **Termux from F-Droid**, copy this repo's `local-termux` folder to the tablet, run `bash setup_termux.sh` once.
2. Each session: `bash start.sh` (leave Termux running), then in SubVoice choose a reference voice and engine `local`.
Main risk: it needs PyTorch (CPU) inside Termux. If `python-torch` is unavailable, this path cannot run and only the `device` engine stays fully local.

### If PyTorch won't install in plain Termux
Use `local-termux/setup_proot.sh` (Ubuntu inside Termux via proot-distro) and start with `start_proot.sh`. The server is still reachable at 127.0.0.1:8002.

## v5 changes
- Screen OCR (ML Kit Devanagari, offline) reads Hindi subtitles from any player; accessibility reading is kept too.
- Cloning reference is taken automatically from ~8 s of the video's own audio (voice id "auto").
- Optional "pause video while dubbing" keeps the dub in sync.
- Debug panel in the app shows what the accessibility service and OCR see.
