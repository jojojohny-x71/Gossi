# Gossi

Android earphone translator. People around you speak **Hindi, Tamil, Kannada or Malayalam**;
Gossi translates it into **English** and speaks it into your earbuds, with a **different English
voice for each speaker**. Everything runs on your phone — no server.

Built for a Nothing Phone (2) + Nothing Ear (a); works on any 64-bit Android 8+ phone.

## Install

1. On your phone, open this repository's **Releases → "Gossi — latest build"** and download `Gossi.apk`
   (built automatically by GitHub Actions on every push).
2. Open it and allow *Install unknown apps* for your browser when asked. New builds install over old ones.
3. Open Gossi → allow microphone and notifications.
4. **Settings → Offline model → Download** (Balanced, 190 MB, once over Wi-Fi).

## Use

1. Put in your Ear (a) and set **noise cancellation to High** (quick settings or the Nothing X app).
2. Choose the language people speak, or leave **Auto**.
3. Tap **Start translating**. Each new person gets a colour and an English voice. Tap a speaker to
   rename them, change their voice, or mute them (e.g. mute yourself).

It keeps running with the screen off; stop it from the notification.

## Settings

| Setting | Options |
|---|---|
| Speech engine | **Gossi offline** (default): speech → English on the phone with OpenAI Whisper via whisper.cpp; works in airplane mode. **Google**: Google speech recognition + Google Translate; needs internet, more accurate for Tamil/Kannada/Malayalam, shows original text. |
| Offline model | Fast 60 MB · Balanced 190 MB · Best 540 MB — downloaded inside the app, resumable |
| Microphone | **Phone mic** (best audio in your ears) or **Earbud mic** (Ear (a) switches to call mode, mono; ANC still works) |
| While Gossi speaks | Music and other apps are turned down, like Apple's AirPods Live Translation |

## How it works

```
mic (phone or earbuds) ─► noise suppression ─► speech segmenter (VAD)
   ─► voiceprint (pitch + cepstrum) ─► speaker A/B/C…
   ─► whisper.cpp on the phone: detect language (hi/ta/kn/ml/en) + translate to English
   ─► Android TTS, voice + pitch per speaker ─► earbuds   (+ live transcript)
```

Code: `app/src/main/java/app/gossi/` — `core/Engine.kt` (pipeline), `offline/` (Whisper engine,
segmenter, model downloads), `audio/` (mic, earbud mic, speaker detection), `speech/` + `translate/`
(Google engine), `tts/`, `ui/`. Native bridge: `app/src/main/cpp/`.

## Testing

- **Self-test** (`.github/workflows/self-test.yml`, runs on push): generates spoken Hindi/Tamil/
  Kannada/Malayalam/English sentences (clean + café noise) and a four-voice conversation, runs them
  through the app's own engine code with each model and scores the English; also installs the APK on
  an Android emulator and plays the conversation through the real app. Results are in the job summary.
- **On your phone:** `tools/phone_test.sh Gossi.apk` installs the app and streams the live log
  (`GossiTranscript` lines show each translation, `GossiWhisper` lines show the time per sentence).

Latest self-test (spoken test sentences; score = chrF closeness to the reference English, 0–100;
time = one sentence on a GitHub cloud CPU, not a phone):

| Model | Hindi | Tamil | Kannada | Malayalam | Time per sentence |
|---|---|---|---|---|---|
| Fast (60 MB) | good | poor | poor | poor | ~0.5 s |
| Balanced (190 MB) | ~85 | ~44 | ~46 | ~50 | ~2 s |
| Best (540 MB) | best | ~66 | good | good | ~7.5 s |

In the four-person conversation, Best got almost every line right (e.g. Tamil "This food is very tasty.",
Kannada "Where is the bus stop?"); all models split the 8 lines correctly and gave 7 of 8 the right speaker.

Longer-term plan (IndicConformer + IndicTrans2 for better Dravidian-language accuracy): [docs/PLAN.md](docs/PLAN.md).
