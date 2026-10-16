# Gossi

Android earphone translator. People around you speak **Hindi, Tamil, Kannada or Malayalam**;
Gossi translates it into **English** and speaks it into your earbuds, with a **different English
voice for each speaker**. Everything runs on your phone — no server.

Built for a Nothing Phone (2) + Nothing Ear (a), works on any Android 8+ phone (speaker
detection needs Android 13+).

## Install

1. On your phone, open this repository's **Releases → "Gossi — latest build"** and download `Gossi.apk`
   (built automatically by GitHub Actions on every push).
2. Open it and allow *Install unknown apps* for your browser when asked.
3. Open Gossi → allow microphone and notifications.

## Use

1. Put in your Ear (a) and set **noise cancellation to High** (quick settings or the Nothing X app).
2. Choose the language people speak, or leave **Auto** (Android 14+ detects it).
3. Tap **Start translating**. Put the phone where it can hear people (on the table, shirt pocket).
4. Each new person gets a colour and an English voice. Tap a speaker to rename them, change
   their voice, or mute them (e.g. mute yourself).

It keeps running with the screen off; stop it from the notification.

### Works offline?
| Part | Online | Offline |
|---|---|---|
| Speech recognition | Google speech service | Download offline speech: Android Settings → search "offline speech recognition" → add Hindi, Tamil, Kannada, Malayalam |
| Translation | Google Translate (free) | Gossi → Settings → *Download offline translation* (Hindi, Tamil, Kannada via ML Kit; Malayalam needs internet) |
| English voices | Phone TTS | Phone TTS (installed voices work offline) |

## How it works

```
Phone mic ─► noise suppression ─► Android SpeechRecognizer (hi/ta/kn/ml-IN)
          └► voiceprint (pitch + cepstrum) ─► speaker A/B/C…
text ─► language from script ─► Google Translate / ML Kit ─► English
English ─► Android TTS, voice + pitch chosen per speaker ─► Ear (a)
```

- **Smart mode** (Android 13+): Gossi records the mic itself (with the phone's noise suppressor),
  fingerprints each voice to tell speakers apart, and streams the audio into the recogniser.
  If the phone's speech service doesn't accept app audio, Gossi switches to **Simple mode**
  automatically (the recogniser uses the mic directly; one voice per language).
- **Noise cancelling**: what you hear is cleaned by the Ear (a)'s hardware ANC; what Gossi hears is
  cleaned by Android's noise suppressor (Settings → *Strong noise suppression* for noisy places).

Code: `app/src/main/java/app/gossi/` — `core/Engine.kt` (pipeline), `speech/` (recogniser loop),
`audio/` (mic, speaker detection), `translate/`, `tts/`, `ui/`.

Longer-term fully-open-source plan (on-device IndicConformer + IndicTrans2 + Kokoro):
[docs/PLAN.md](docs/PLAN.md).
