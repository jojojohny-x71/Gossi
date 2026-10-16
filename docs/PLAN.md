# Gossi — Multilingual Earphone Translator: Plan & Spec

> **Goal:** You wear earphones. People around you talk in up to ~5 different languages.
> Gossi cuts the background noise, works out who is speaking and in what language,
> translates everything into **one language you understand**, and speaks it into your ears
> with a **different voice for each speaker** so you can tell them apart.
> Offline-first, built on open-source components wherever possible.

---

## 1. What the product does

```
 People talking (EN, HI, ES, AR, ZH ...)  +  traffic / crowd / music
                        │
                 [ phone / earbud mic ]
                        │
   1. Clean up       noise suppression + voice activity detection
   2. Who?           speaker diarization  → Speaker A, B, C ...
   3. Which lang?    language ID (limited to the languages you pick)
   4. What said?     speech-to-text (ASR)
   5. Translate      → your language (e.g. English or Malayalam)
   6. Speak it       text-to-speech, one distinct voice per speaker
                        │
                 [ your earbuds, ANC on ]   +  live transcript on screen
```

### Core user stories
| # | As a user I want to… | Priority |
|---|---|---|
| U1 | Hear background noise cut out while I listen | Must |
| U2 | Hear anything said near me translated into my language | Must |
| U3 | Tell speakers apart by voice (Speaker A sounds different from B) | Must |
| U4 | Use it with no internet (flight, abroad, bad signal) | Must |
| U5 | See a live transcript (original + translation) on my phone | Must |
| U6 | Choose the languages expected in this conversation (up to 5) | Must |
| U7 | Not hear my own speech translated back to me | Should |
| U8 | Rename speakers ("Speaker B" → "Ravi") and pick their voices | Should |
| U9 | Use a more accurate "home server / cloud" mode when online | Should |
| U10 | Translated voice that resembles the original speaker (voice cloning) | Could |
| U11 | Save / export conversation transcripts | Could |

---

## 2. An important reality check: "noise cancelling"

There are **two different things** people mean by noise cancelling, and the app needs both:

1. **Acoustic ANC (what reaches your ears).** This is done by the *earbud hardware*
   (AirPods Pro, Galaxy Buds, Sony WF-1000XM, Pixel Buds Pro, Nothing Ear...).
   A phone app **cannot** do real acoustic ANC over Bluetooth — the latency is far too high.
   → The app asks the user to switch ANC **on** (Android/Samsung/Sony let some apps toggle
   modes via vendor SDKs; on iOS the user toggles it). With ANC on, the user hears
   *only* Gossi's translated voices.

2. **Software noise suppression (what the AI hears).** We clean the *microphone* signal
   before speech recognition so the models aren't confused by background noise.
   This is fully in our control → **DeepFilterNet 3** or **RNNoise** (both open source).

Optional: a **"pass-through mix"** slider that blends a little denoised original audio
under the translation so you still sense the room (like transparency mode, but clean).

### Microphone choice (matters a lot)
| Mic source | Quality | Notes |
|---|---|---|
| Earbud mic over classic Bluetooth (HFP) | Poor (8–16 kHz, mono) | Also forces earbud *playback* into low-quality call mode. Avoid for v1. |
| **Phone's built-in mics** | **Good** | **Default for v1.** Phone on the table / in hand, earbuds stay in high-quality A2DP for playback. |
| Earbud mic over **LE Audio (LC3)** | Good | Newer phones/buds (Android 13+, Pixel/Samsung). Phase 3. |
| External clip-on / USB-C mic | Best | Optional accessory for meetings. |

---

## 3. Architecture

### 3.1 Pipeline (per audio stream)

```
Mic 16 kHz PCM
  └─► Noise suppression (DeepFilterNet3 / RNNoise)            ~10 ms frames
       └─► VAD (Silero VAD)  → speech segments (end-of-utterance after ~400 ms silence)
            ├─► Speaker embedding (ECAPA / CAM++ / WeSpeaker) → online clustering → speaker_id
            │      └─► "is this me?" check against the user's enrolled voice → drop if yes
            ├─► Language ID (Whisper LID, restricted to the session's chosen languages)
            └─► ASR (Whisper / per-language streaming Zipformer)  → source text
                 └─► MT  source text → target language            → translated text
                      └─► TTS with voice = voice_for(speaker_id)   → audio
                           └─► Playback queue (ducking, speed-up if backlog) → earbuds
                                └─► UI transcript (original + translation, colour per speaker)
```

### 3.2 Three run modes (same app, same pipeline interface)

| Mode | Where models run | When | Quality | Latency |
|---|---|---|---|---|
| **A. Offline (default)** | 100 % on the phone | Always available | Good | 1.5–3 s |
| **B. Home server** | Your own PC / Mac / GPU box, reached over LAN or Tailscale/WireGuard | At home/office, or anywhere with Tailscale | Best (large models) | 1–2 s |
| **C. Cloud (optional)** | Self-hosted GPU VM, or paid APIs as last resort | When online and phone is weak | Best | 1–2 s + network |

Every pipeline stage is behind an interface (`Denoiser`, `Vad`, `Diarizer`, `LangId`,
`Asr`, `Translator`, `Tts`), so each stage can be local or remote independently
(e.g. local ASR + remote voice-cloning TTS).

### 3.3 Client app

**Recommendation: Android first, native Kotlin + a shared C/C++ inference core.**

Why Android first: freer audio routing (choose input mic vs. output device separately),
easy side-loading of large model files, LE Audio support, vendor ANC SDKs, cheaper test devices.
iOS follows in Phase 3 reusing the same C/C++ core (Swift UI shell).

| Layer | Choice | Why |
|---|---|---|
| UI | Kotlin + Jetpack Compose (Android), SwiftUI (iOS later) | Native audio APIs, best latency |
| Audio I/O | Oboe (Android, Apache-2.0) / AVAudioEngine (iOS) | Low-latency capture & playback |
| Speech toolkit | **sherpa-onnx** (Apache-2.0) | One library for VAD, ASR, speaker ID, language ID, TTS, with Android/iOS/Flutter bindings. Huge shortcut. |
| Whisper runtime | **whisper.cpp** (MIT) | Quantized multilingual Whisper on CPU/GPU/NPU |
| Translation runtime | **CTranslate2** (MIT) or **Bergamot** (MPL-2.0) or **llama.cpp** (MIT) | Fast int8 MT on mobile CPU |
| Background service | Android Foreground Service (type `microphone`) | Keeps running with screen off |
| Storage | Room/SQLite for transcripts; model files in app storage | Offline |
| Model manager | Downloadable "language packs" with checksums | Keeps the base app small |

> Alternative if you strongly want one codebase: **Flutter** + sherpa-onnx's Flutter plugin.
> Faster to build both platforms, slightly less control over audio. Native is recommended
> for a real-time audio product.

### 3.4 Backend (Modes B & C)

| Concern | Choice |
|---|---|
| Language / framework | Python 3.11 + **FastAPI** |
| Transport | **WebSocket** streaming (Opus-encoded 20 ms frames up, JSON + Opus audio down). gRPC is an option later. |
| ASR | **faster-whisper** (MIT, CTranslate2) with Whisper **large-v3-turbo**; streaming via the `whisper_streaming` / `SimulStreaming` approach (local-agreement policy) |
| Diarization | **pyannote.audio 3.x** (MIT code; model needs HF terms acceptance) or **NVIDIA NeMo Sortformer** (Apache-2.0) |
| Translation | Qwen3 / Gemma-class LLM via **vLLM** or **llama.cpp server**, or **NLLB-200** (non-commercial) / **MADLAD-400** (Apache-2.0) via CTranslate2 |
| TTS + voice cloning | **OpenVoice V2** (MIT) tone converter over Kokoro/MeloTTS; **CosyVoice 2** (Apache-2.0) |
| Packaging | Docker Compose; one image with CUDA, one CPU-only |
| Auth (remote) | Device pairing via QR code → per-device token; TLS; prefer Tailscale for zero-config private networking |
| Privacy | Audio processed in memory, never stored unless user enables history |

The backend is **optional** — the product must be fully usable in Mode A.

---

## 4. Model choices (open source first)

### 4.1 Recommended stack per stage

| Stage | On-device (Mode A) | Server (Mode B/C) | License |
|---|---|---|---|
| Noise suppression | **DeepFilterNet 3** (ONNX) — fallback **RNNoise** for low-end phones | DeepFilterNet 3 | MIT/Apache-2.0 · BSD |
| VAD | **Silero VAD v5** | Silero VAD | MIT |
| Speaker embedding / diarization | **3D-Speaker CAM++** or **WeSpeaker ResNet34** via sherpa-onnx + online agglomerative clustering | **pyannote 3.1** or **NeMo Sortformer** | Apache-2.0 · MIT |
| Language ID | **Whisper LID** (restricted to chosen languages) — or SpeechBrain **VoxLingua107 ECAPA** | Whisper large-v3 LID | MIT · Apache-2.0 |
| ASR | **Whisper small / medium (q5/q8)** via whisper.cpp; for latency-critical languages, **streaming Zipformer** models via sherpa-onnx | **Whisper large-v3-turbo** via faster-whisper | MIT · Apache-2.0 |
| Translation | **Bergamot / OPUS-MT** (small, fast, per language pair) via CTranslate2; **IndicTrans2** for Indian languages; small LLM (**Qwen3-1.7B / 4B**, q4) for "any-to-any" | **Qwen3-8B/14B** or **Gemma 3 12B**, or **MADLAD-400** | MIT/CC-BY · MIT · Apache-2.0 |
| TTS | **Piper** (many languages, many voices, very fast) and **Kokoro-82M** (higher quality, fewer languages) via sherpa-onnx | Kokoro / MeloTTS + **OpenVoice V2** cloning; **CosyVoice 2** | MIT · Apache-2.0 |

### 4.2 License watch-list (important if this ever becomes commercial)
These are excellent but **non-commercial** — fine for a personal project, avoid for a product:
- **NLLB-200** and **SeamlessM4T / SeamlessStreaming** (Meta) — CC-BY-NC 4.0
- **Coqui XTTS-v2** — Coqui Public Model License (non-commercial)
- **F5-TTS** weights — CC-BY-NC

Not open source but free & offline (acceptable fallback): **Google ML Kit on-device translation**.

### 4.3 "One-shot" alternative worth prototyping
**Whisper `task=translate`** goes straight from any language → English in one model
(skipping separate MT). If your target language is English, this is the simplest v0.
It only outputs English, so it isn't the long-term answer if you want e.g. Malayalam/Hindi output.

---

## 5. The "different voice per speaker" feature

**v1 — Voice palette (offline, simple, reliable)**
- The target-language TTS ships with a palette of 6–8 clearly different voices
  (male/female, low/high pitch, different accents/dialects, e.g. en-US, en-GB, en-IN, en-AU).
- When a new speaker is detected, estimate pitch (F0) and rough gender from their audio and
  assign the closest *unused* voice from the palette → Speaker A always sounds like Voice 3.
- Optional: pick the voice's **dialect/accent by source language** (e.g. Spanish speaker →
  voice with a Spanish-accented style if available) so origin is audible.
- User can override in the UI (rename speaker, choose voice).

**v2 — Voice resemblance (server, Phase 3)**
- After ~5–10 s of a speaker's audio, build a voice embedding and use **OpenVoice V2**
  tone-colour conversion (or CosyVoice 2 zero-shot) so the translation sounds *like them*.
- Requires opt-in and a visible indicator — this is voice cloning, so consent matters.

---

## 6. Handling the hard cases

| Problem | Approach |
|---|---|
| **Short utterances → wrong language detected** | User picks the expected languages (≤5) at session start; LID chooses only among those. Keep a per-speaker language prior (Speaker B spoke Hindi last 3 times → likely Hindi). |
| **Code-switching** (Hinglish, Spanglish) | Whisper handles mixed speech reasonably; let MT/LLM translate mixed text as-is. |
| **Two people talking at once** | v1: diarization picks dominant speaker; output queued. v3: speech separation (SepFormer / MossFormer2, Apache-2.0) on server. |
| **Backlog (people talk faster than TTS speaks)** | Playback queue with: TTS speed-up 1.0→1.3×, drop filler/duplicate segments, summarise if queue > N seconds (LLM mode). Transcript always shows everything. |
| **Whisper hallucinating on noise/silence** | Only feed VAD-confirmed speech; drop low-confidence segments (`no_speech_prob`, avg logprob thresholds); denoise first. |
| **Hearing yourself** | Enroll your voice once (10 s); segments matching your embedding are skipped (or optionally translated *outward* through the phone speaker — future "two-way" mode). |
| **Battery & heat** | Models loaded once; VAD gates everything (ASR/MT/TTS only run on speech); use NNAPI/GPU/NPU delegates where available; "low power" profile with smaller models. |
| **Phone too weak** | Automatic profile selection at install (benchmark run): Low / Mid / High model sets. |

---

## 7. Non-functional requirements

| Requirement | Target |
|---|---|
| End-to-end latency (end of utterance → start of translated audio) | p50 ≤ 2.5 s offline on a 2023+ mid/high-end phone; ≤ 1.5 s home-server mode |
| Translation quality | Understandable meaning for everyday conversation; measured with COMET/chrF on a test set |
| Speaker separation | Correct speaker label on ≥ 85 % of segments for 2–4 speakers in a quiet-ish room |
| Battery | ≥ 2 h continuous listening on a 4500 mAh phone in Mode A |
| Storage | Base app < 60 MB; each language pack 150–600 MB; "full 5-language" set ≈ 1.5–2.5 GB |
| Privacy | Mode A: no audio or text leaves the device, no account required, no analytics by default |
| Supported devices (v1) | Android 10+ (API 29+), arm64, ≥ 6 GB RAM recommended (4 GB in low profile) |

### Rough latency budget (Mode A)
| Step | Time |
|---|---|
| End-of-speech detection (VAD silence) | 300–500 ms |
| ASR on a 3–5 s utterance (Whisper small q5) | 400–1000 ms |
| Translation (OPUS-MT int8) / small LLM | 50–200 ms / 300–800 ms |
| TTS time-to-first-audio (Piper/Kokoro) | 100–300 ms |
| Bluetooth output latency | 150–250 ms |
| **Total** | **≈ 1.0–2.8 s** |

---

## 8. Core data model

```kotlin
data class Session(
    val id: String,
    val targetLanguage: String,          // e.g. "en", "ml", "hi"
    val expectedLanguages: List<String>, // ≤ 5, e.g. ["es","ar","zh","hi","fr"]
    val mode: RunMode,                   // OFFLINE, HOME_SERVER, CLOUD
    val startedAt: Instant,
)

data class Speaker(
    val id: String,                      // "spk_1"
    val sessionId: String,
    var displayName: String,             // "Speaker A" → user can rename
    val embedding: FloatArray,           // running mean voice embedding
    var voiceId: String,                 // TTS voice from palette
    var languagePrior: Map<String, Float>,
    val isSelf: Boolean = false,
)

data class Segment(
    val id: String,
    val sessionId: String,
    val speakerId: String,
    val startMs: Long, val endMs: Long,
    val sourceLang: String, val langConfidence: Float,
    val sourceText: String, val asrConfidence: Float,
    val translatedText: String,
    val spoken: Boolean,                 // played to earbuds or skipped (backlog/self)
)
```

### Streaming protocol (Mode B/C, WebSocket)
- **Client → server:** `hello {session config, device token}`, then binary Opus frames (20 ms).
- **Server → client events:**
  `vad {start|end}`, `partial {speaker, lang, text}`, `final {segment}`,
  `translation {segment_id, text}`, `audio {segment_id, opus_chunk}`, `speaker_new {id, voice}`, `error`.
- The client can ask the server for text only and run TTS locally (saves bandwidth, keeps voices consistent).

---

## 9. App screens (v1)

1. **Onboarding** — pick your language, download its pack, enroll your voice (10 s), check earbuds/ANC.
2. **Session setup** — choose up to 5 expected languages (with "auto" option), mode (Offline/Home/Cloud).
3. **Live screen** — big start/stop, live transcript as chat bubbles coloured per speaker
   (original text small, translation large), per-speaker mute, volume of pass-through mix.
4. **Speakers sheet** — rename speakers, change voice, merge two speakers that were split by mistake.
5. **Language packs** — download/delete models, show sizes, choose Low/Mid/High profile.
6. **History** (optional) — past sessions, export as text/SRT.

---

## 10. Repository layout (proposed)

```
gossi/
├── docs/                 # this plan, ADRs, benchmarks
├── prototype/            # Phase 0 Python pipeline (laptop) + evaluation scripts
├── core/                 # C/C++ inference core (sherpa-onnx, whisper.cpp, CTranslate2 glue)
├── android/              # Kotlin + Compose app, JNI bindings to core/
├── ios/                  # SwiftUI app (Phase 3)
├── server/               # FastAPI streaming backend + Dockerfiles (Mode B/C)
└── models/               # model-pack manifests (URLs, checksums, sizes, licenses) — no weights in git
```

---

## 11. Roadmap

### Phase 0 — Desktop prototype (1–2 weeks)
- Python script: mic → DeepFilterNet → Silero VAD → faster-whisper → MT → Piper → speakers/headphones.
- Add speaker embeddings + clustering + voice palette.
- Record a **test set**: 10–20 min of real conversations in your 5 target languages, noisy + quiet.
- Measure latency per stage and accuracy (WER, chrF/COMET, diarization error) → pick models.
- **Exit:** you can sit with a multilingual group and follow along through laptop + earbuds.

### Phase 1 — Android offline MVP (4–6 weeks)
- Kotlin app, foreground service, Oboe audio, phone mic in → earbuds out.
- sherpa-onnx (VAD, speaker ID, TTS) + whisper.cpp (ASR/LID) + CTranslate2/Bergamot (MT).
- One target language, 2–3 source languages, voice palette, live transcript.
- Language pack downloader with checksums.
- **Exit:** works in airplane mode on a mid/high-end phone with p50 latency ≤ 3 s.

### Phase 2 — Multi-speaker & home server (4 weeks)
- 5 source languages, language priors, self-voice filtering, speaker rename/merge.
- Device benchmark → Low/Mid/High profiles; battery optimisation.
- FastAPI + WebSocket server in Docker; QR pairing; automatic fallback to offline when disconnected.
- **Exit:** reliable 4-speaker, 5-language dinner-table demo.

### Phase 3 — Quality & reach (6+ weeks)
- Streaming partial results (start translating before the speaker finishes).
- Voice resemblance via OpenVoice V2 / CosyVoice 2 (server, opt-in).
- Overlapped-speech separation on server; LE Audio earbud mic; vendor ANC toggles.
- iOS app on the shared core.

### Phase 4 — Polish
- History/export, accessibility (captions-only mode for deaf/hard-of-hearing users),
  two-way mode (your speech → their language through phone speaker), widget/quick-tile.

---

## 12. Risks & open questions

| Risk | Mitigation |
|---|---|
| On-device speed varies widely across phones | Benchmark-based profiles; home-server mode |
| Whisper accuracy weaker on low-resource languages | Per-language model overrides (e.g. IndicTrans2, language-specific fine-tuned Whisper) |
| Bluetooth mic quality | Use phone mic by default; LE Audio later |
| Recording consent / local law | Clear in-app notice; no recording stored by default; voice cloning opt-in only |
| Model licences | Licence field in every model-pack manifest; CI check blocks NC models in "commercial" build flavour |

**Decisions needed from you:**
1. Which **target language** do you want to hear (English, Malayalam, Hindi, …)? This drives the MT and TTS choices most.
2. Which **5 source languages** do you expect most often?
3. Which **phone + earbuds** will you test on first (Android or iPhone)?
4. Personal project, or possibly **commercial** later? (Decides whether NLLB/XTTS-class models are allowed.)
5. Do you have a **PC with an NVIDIA GPU** for home-server mode?
