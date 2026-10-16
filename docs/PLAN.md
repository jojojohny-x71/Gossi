# Gossi — Offline Indian-Language → English Earphone Translator: Plan & Spec

> **Goal:** You wear your earbuds. People around you speak **Hindi, Tamil, Kannada or
> Malayalam** (often mixed with English). Gossi removes background noise, works out who is
> speaking and in which language, translates it into **English**, and speaks it into your ears
> with a **different voice for each speaker**.
> **100 % on the phone, no internet, no server.** Open-source models only.

## 0. Fixed decisions

| Decision | Value |
|---|---|
| Run mode | **On-device only** (no home server, no cloud) |
| Target language (what you hear) | **English** |
| Source languages | **Hindi, Tamil, Kannada, Malayalam** (+ English, which is passed through untranslated) |
| Phone | **Nothing Phone (2)** — Snapdragon 8+ Gen 1, 8/12 GB RAM, Android (Nothing OS) |
| Earbuds | **Nothing Ear (a)** — hardware ANC, Bluetooth A2DP (AAC/LDAC) |
| Platform | **Android only**, native Kotlin |
| Licensing | Only commercially-friendly licences (MIT / Apache-2.0 / BSD) so the app can be shipped later |

> If the phone is actually a **Phone (2a)** (Dimensity 7200 Pro) rather than **Phone (2)**,
> everything still applies — the app picks the "Mid" model profile (§4.3) automatically.

---

## 1. What the user experiences

```
 Ravi (Hindi) · Priya (Tamil) · Arun (Kannada) · Meera (Malayalam)  +  traffic / fan / crowd
                                   │
                      [ Nothing Phone (2) mics ]
                                   │
   1. Clean up        noise suppression + speech detection
   2. Who?            speaker A / B / C / D
   3. Which language? hi / ta / kn / ml / en
   4. What was said?  Indian-language speech recognition
   5. Translate       → English
   6. Speak it        English TTS, a distinct voice per speaker
                                   │
                [ Nothing Ear (a), ANC ON ]  +  live transcript on screen
```

### User stories
| # | As a user I want to… | Priority |
|---|---|---|
| U1 | Hear the room's background noise removed (earbud ANC on) | Must |
| U2 | Hear Hindi / Tamil / Kannada / Malayalam speech in English | Must |
| U3 | Tell speakers apart by their English voice | Must |
| U4 | Everything works in airplane mode | Must |
| U5 | See a live transcript (original script + English) | Must |
| U6 | Not hear my own speech translated back to me | Should |
| U7 | Rename speakers and choose their voices | Should |
| U8 | Mixed speech (Hinglish, Tanglish, etc.) handled sensibly | Should |
| U9 | English speech left as-is (optionally re-voiced or just shown on screen) | Should |
| U10 | Save / export transcripts | Could |

---

## 2. Noise cancelling with Nothing Ear (a)

Two separate jobs:

1. **What you hear → Nothing Ear (a) hardware ANC.** A phone app cannot do acoustic noise
   cancelling over Bluetooth (too much delay). The earbuds do it. Nothing does not publish an
   SDK for apps to switch ANC modes, so the app **asks you to set ANC to "High"** in Nothing X
   / quick settings, and shows a reminder on the start screen. With ANC on, you hear mostly
   Gossi's English voices.
2. **What the AI hears → software noise suppression on the phone mic.** Run before speech
   recognition:
   - **GTCRN** (tiny, < 1 MB, built into sherpa-onnx) — default, almost free on battery.
   - **DeepFilterNet 3** (MIT/Apache) — stronger, for very noisy places (toggle "Noisy mode").

Optional **"room mix"** slider: blends a little *denoised* original sound under the
translation so you still sense who is talking (like transparency mode, but clean).

### Microphone
- **Use the phone's built-in mics, not the earbud mics.** Earbud mics over Bluetooth force the
  earbuds into low-quality "call" mode (HFP, 16 kHz, mono) which ruins playback quality too.
- Phone sits on the table or in your shirt pocket facing out; Ear (a) stays on high-quality
  A2DP for listening.
- Android: record with `AudioSource.UNPROCESSED` (or `VOICE_RECOGNITION`) and route output
  to the Bluetooth A2DP device.

---

## 3. Architecture

### 3.1 Pipeline

```
Phone mic 16 kHz mono PCM  (Oboe, 10 ms frames)
 └─► Noise suppression        GTCRN  (or DeepFilterNet3 in Noisy mode)
      └─► VAD                 Silero VAD v5 → utterance ends after ~400 ms silence (max 8 s)
           ├─► Speaker ID     CAM++ / WeSpeaker embedding → online clustering → speaker_id
           │     └─► Self check: matches your enrolled voice?  → skip
           ├─► Language ID    VoxLingua107 ECAPA, restricted to {hi, ta, kn, ml, en}
           │                  + per-speaker language prior
           └─► ASR            IndicConformer (hi/ta/kn/ml)  → text in native script
                └─► Translate IndicTrans2 indic→en (distilled, int8)   → English text
                     └─► TTS  Kokoro-82M English, voice = voice_for(speaker_id)
                          └─► Playback queue (ducking, speed-up when behind) → Ear (a)
                               └─► Transcript UI (colour per speaker)
English utterance  → skip ASR-translate; optional: show only / re-voice
```

All stages implement small Kotlin interfaces (`Denoiser`, `Vad`, `SpeakerId`, `LangId`,
`Asr`, `Translator`, `Tts`) so any model can be swapped after benchmarking.

### 3.2 App stack

| Layer | Choice | Licence |
|---|---|---|
| UI | Kotlin + Jetpack Compose | — |
| Audio I/O | **Oboe** (low-latency capture/playback) | Apache-2.0 |
| Background | Foreground Service (`foregroundServiceType="microphone"`), runs with screen off | — |
| Speech toolkit | **sherpa-onnx** — VAD, denoise, speaker embedding, TTS (Kokoro), ONNX ASR | Apache-2.0 |
| Inference runtime | **ONNX Runtime** (CPU + XNNPACK; QNN/NNAPI for the Snapdragon NPU where it helps) | MIT |
| Translation runtime | **CTranslate2** (int8) for IndicTrans2 — or ONNX Runtime export | MIT |
| Indic text handling | **IndicNLP / IndicTransToolkit** pre/post-processing (ported to Kotlin/C++) | MIT |
| Storage | Room (SQLite) for sessions/transcripts; models in app-private storage | — |
| Model manager | One-time download of a "language pack" (checksummed), then fully offline | — |

Native code (sherpa-onnx, CTranslate2) is called over JNI from a single `PipelineEngine`
running on dedicated threads: audio thread → recognition thread → TTS thread.

---

## 4. Model choices (all on-device, all commercial-friendly)

### 4.1 Why not just Whisper?
Whisper is good for Hindi but **weak for Tamil, Kannada and Malayalam** (high word error
rates), and its autoregressive decoder is slow on a phone. **AI4Bharat's** models are trained
specifically on Indian-language speech (IndicVoices, Kathbath, etc.) and are much better here.

### 4.2 Recommended stack

| Stage | Model | Approx. size (int8) | Licence |
|---|---|---|---|
| Noise suppression | **GTCRN** (default) / **DeepFilterNet 3** (noisy mode) | < 1 MB / ~10 MB | MIT / MIT-Apache |
| VAD | **Silero VAD v5** | 2 MB | MIT |
| Speaker embedding | **3D-Speaker CAM++** or **WeSpeaker ResNet34** (sherpa-onnx) | 25–30 MB | Apache-2.0 |
| Language ID | **SpeechBrain VoxLingua107 ECAPA** (covers hi, ta, kn, ml, en) exported to ONNX | ~40–80 MB | Apache-2.0 |
| ASR | **AI4Bharat IndicConformer** — multilingual 600M model, *or* the smaller per-language models for hi/ta/kn/ml (CTC/RNN-T, non-autoregressive → fast) exported from NeMo to ONNX | ~600 MB multilingual, or ~4 × 120–150 MB | MIT |
| Translation | **AI4Bharat IndicTrans2 indic→en distilled (~200M)** via CTranslate2 int8 | ~250 MB | MIT |
| TTS | **Kokoro-82M** (English; one model with many voices — American/British, male/female) via sherpa-onnx | ~90–330 MB | Apache-2.0 |
| Fallback TTS | **Piper** English voices (even faster, lower quality) | ~60 MB/voice | MIT |

**Total download ≈ 1.0–1.4 GB.** Peak RAM ≈ 1.2–1.6 GB, comfortable on an 8/12 GB Phone (2).

> **Alternative ASR to benchmark in Phase 0:** Vistaar / IndicWhisper (Whisper fine-tuned on
> Indian languages). Better than plain Whisper, but slower than IndicConformer on-device.
> For Hindi only, Whisper `task=translate` (speech → English in one step) is also worth one test.

### 4.3 Device profiles (auto-selected by a 30-second benchmark at first launch)

| Profile | Device class | ASR | MT | TTS |
|---|---|---|---|---|
| **High** | Phone (2), 8+ Gen 1 | IndicConformer multilingual 600M | IndicTrans2 distilled 200M | Kokoro |
| **Mid** | Phone (2a)/(3a)-class | per-language IndicConformer (load only the 2 most-used + swap) | IndicTrans2 distilled | Kokoro (int8) |
| **Low / battery saver** | any | per-language models | IndicTrans2 distilled | Piper |

### 4.4 Code-mixing (very common in India)
- People say "Meeting kal postpone ho gaya, so we'll do it Monday."
- IndicConformer outputs English words in native script or Latin depending on training;
  IndicTrans2 handles most mixed input. Post-process: keep recognised English tokens as-is.
- If the language-ID score is split between English and an Indian language → treat as the
  Indian language (translation of mixed text is safe; skipping it is not).
- Test set (§8) must include code-mixed speech for all four languages.

---

## 5. A different English voice per speaker

**Voice palette (v1):** Kokoro's English voices give 8+ clearly different voices
(American/British, male/female, different pitch). One Kokoro model serves all of them, so
adding speakers costs no extra memory.

1. New speaker detected → estimate pitch (F0) from their audio → assign the closest unused
   voice of matching register (low voice → low voice).
2. Speaker keeps that voice for the whole session; the transcript bubble uses a matching colour.
3. Optional "**accent by language**" setting: e.g. Hindi speakers → voice group 1, Tamil →
   group 2… so you can also *hear* which language the person spoke.
4. Phase 2 experiment: Kokoro also has Hindi voices — test whether they read English text with
   a natural Indian-English accent; if yes, offer an "Indian English voices" palette.
5. In the Speakers sheet you can rename ("Speaker B" → "Priya"), change voice, merge two
   speakers, or mute one.

Voice *cloning* (sounding like the real person) is not planned on-device — current
open models are too heavy for real time on a phone. Revisit later.

---

## 6. Hard cases

| Problem | Approach |
|---|---|
| Short utterances → wrong language | LID only chooses among {hi, ta, kn, ml, en}; per-speaker language prior (if Speaker C spoke Malayalam 3 times, bias towards ml); you can pin a language per speaker. |
| Similar Dravidian languages (Tamil/Malayalam/Kannada) confused | Prior + pinning; Phase 2: run a second-pass check — if ASR confidence is low, re-decode with the next-best language. |
| Two people talking at once | v1: dominant speaker only, queue output. (Overlap separation is too heavy on-device for now.) |
| People talk faster than TTS can speak | Queue with TTS speed 1.0→1.3×, drop very short fillers ("haan", "seri", "accha"), always show full transcript. |
| Your own voice | 10-second enrolment; segments matching your voice are skipped. |
| Battery / heat | VAD gates everything (models run only on speech); models loaded once; "battery saver" profile; target ≥ 2 h continuous. |
| Bluetooth delay | Ear (a) A2DP adds ~150–250 ms; use AAC rather than LDAC for lower, more stable latency. |

---

## 7. Non-functional targets (Phone (2) + Ear (a))

| Requirement | Target |
|---|---|
| Latency (speaker stops → English audio starts) | p50 ≤ 2.0 s, p95 ≤ 3.5 s |
| ASR accuracy | WER ≤ ~20 % (hi), ≤ ~30 % (ta/kn/ml) on conversational test set |
| Translation | Meaning understandable for everyday conversation (check with chrF/COMET + your own judgement) |
| Speaker labels | ≥ 85 % correct for 2–4 speakers in a normal room |
| Battery | ≥ 2 h continuous listening |
| Offline | Works in airplane mode after the one-time pack download; no account, no analytics |
| App size | APK < 60 MB + language pack ≈ 1.0–1.4 GB |

### Latency budget
| Step | Time |
|---|---|
| End-of-speech detection | 300–450 ms |
| Language ID (parallel with ASR) | ~50 ms |
| ASR, IndicConformer on 3–5 s audio | 150–400 ms |
| IndicTrans2 distilled, one sentence | 100–250 ms |
| Kokoro time-to-first-audio (sentence-chunked) | 150–300 ms |
| Bluetooth to Ear (a) | 150–250 ms |
| **Total** | **≈ 0.9–1.7 s** typical |

---

## 8. Testing & evaluation

- **Public test sets:** AI4Bharat **IndicVoices** / **Kathbath**, Google **FLEURS** (hi, ta,
  kn, ml) for ASR; **IN22** (IndicTrans2 benchmark) for translation.
- **Your own test set (most important):** 10–20 min per language of real conversations
  recorded on the Nothing Phone (2) — quiet room, restaurant/street noise, code-mixed,
  2–4 speakers. Keep reference transcripts + English translations.
- **Benchmark harness:** script that runs the full pipeline over the recordings and reports
  per-stage latency, WER, chrF, language-ID accuracy and speaker error rate.

---

## 9. Data model

```kotlin
enum class Lang { HI, TA, KN, ML, EN }

data class Session(
    val id: String,
    val expectedLanguages: Set<Lang> = setOf(Lang.HI, Lang.TA, Lang.KN, Lang.ML, Lang.EN),
    val profile: Profile,                // HIGH, MID, LOW
    val startedAt: Instant,
)

data class Speaker(
    val id: String,
    val sessionId: String,
    var displayName: String,             // "Speaker A" → "Priya"
    val embedding: FloatArray,           // running mean voice embedding
    var voiceId: Int,                    // Kokoro speaker id
    var languagePrior: Map<Lang, Float>,
    var pinnedLanguage: Lang? = null,
    var muted: Boolean = false,
    val isSelf: Boolean = false,
)

data class Segment(
    val id: String,
    val sessionId: String,
    val speakerId: String,
    val startMs: Long, val endMs: Long,
    val lang: Lang, val langConfidence: Float,
    val sourceText: String,              // native script, e.g. Devanagari / Tamil script
    val asrConfidence: Float,
    val englishText: String,
    val spoken: Boolean,                 // played, or skipped (self / backlog / muted)
)
```

---

## 10. Screens

1. **Onboarding** — download language pack (Wi-Fi, one time), enrol your voice (10 s),
   connect Ear (a) and turn ANC on (with screenshots of the Nothing X setting).
2. **Live** — big start/stop button, chat-style transcript coloured per speaker (small native
   script line + large English line), room-mix slider, "Noisy mode" toggle.
3. **Speakers** — rename, change voice, pin language, merge, mute.
4. **Settings** — profile (auto/High/Mid/Low), English voices palette, filler-word skipping,
   speech speed, show/hide English-speaker re-voicing.
5. **History** (optional) — past sessions, export as text.

Also: a Quick Settings tile to start/stop listening without opening the app.

---

## 11. Repository layout

```
gossi/
├── docs/          # this plan, decision records, benchmark results
├── prototype/     # Phase 0: Python pipeline + evaluation harness (runs on any laptop/cloud VM)
├── tools/         # model export/quantisation scripts (NeMo → ONNX, IndicTrans2 → CTranslate2)
├── android/       # Kotlin + Compose app, JNI bridge, foreground service
│   └── native/    # CMake: sherpa-onnx, onnxruntime, ctranslate2 glue
└── models/        # language-pack manifest (URLs, SHA-256, sizes, licences) — no weights in git
```

---

## 12. Roadmap

### Phase 0 — Model bake-off (1–2 weeks)
Runs on a laptop or a cloud VM (no GPU needed, it's only for choosing models — the
final app is phone-only).
- Export IndicConformer, IndicTrans2, VoxLingua107 LID to ONNX/CTranslate2 int8.
- Python pipeline replicating the app pipeline; run it on FLEURS/IndicVoices + your recordings.
- Compare IndicConformer vs IndicWhisper vs Whisper on hi/ta/kn/ml (WER, speed).
- **Exit:** model list frozen, int8 files validated, expected accuracy known.

### Phase 1 — Android MVP (4–6 weeks)
- Kotlin app, Oboe capture, foreground service, JNI to sherpa-onnx + ONNX Runtime + CTranslate2.
- Pipeline: denoise → VAD → LID → IndicConformer → IndicTrans2 → Kokoro (one voice) → Ear (a).
- Live transcript, language-pack downloader.
- **Exit:** a Hindi or Tamil speaker → English in your ears in airplane mode, p50 ≤ 2.5 s.

### Phase 2 — Multi-speaker & polish (4 weeks)
- Speaker embeddings + clustering + voice palette, self-voice filtering, Speakers screen.
- Language priors / pinning, code-mixing handling, filler skipping, backlog speed-up.
- Device benchmark → profiles; NPU (QNN) acceleration; battery tuning.
- **Exit:** 4 people speaking 4 languages at a table, each heard in a distinct English voice.

### Phase 3 — Quality
- Streaming partial ASR (start translating long sentences before they end).
- Indian-English voice palette, DeepFilterNet "Noisy mode", history/export, Quick Settings tile.
- Optional: captions-only mode (accessibility), two-way mode (your English → Hindi/Tamil/… via phone speaker using AI4Bharat Indic TTS).

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| IndicConformer ONNX export / NPU support is fiddly | Phase 0 de-risks it first; CPU int8 fallback is fast enough for CTC models |
| Kannada/Malayalam accuracy lower than Hindi | Per-language model choice, language pinning, test on real recordings early |
| Nothing Ear (a) ANC can't be controlled by apps | Clear onboarding prompt; app works regardless |
| Battery drain in long sessions | VAD gating, battery-saver profile, thermal throttling detection |
| Recording people / consent | On-screen notice, nothing stored by default, transcripts saved only if enabled |
