"""Builds the self-test audio: spoken Hindi/Tamil/Kannada/Malayalam/English sentences (Google TTS),
noisy copies, and a four-speaker conversation. Usage: python make_audio.py OUT_DIR"""
import os
import subprocess
import sys

import numpy as np
from gtts import gTTS

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
SR = 16000

# id, language, sentence, reference English
SENTENCES = [
    ("hi1", "hi", "आज मौसम बहुत अच्छा है, चलो पार्क चलते हैं।", "The weather is very nice today, let's go to the park."),
    ("hi2", "hi", "मुझे रेलवे स्टेशन जाने का रास्ता बताइए।", "Please tell me the way to the railway station."),
    ("ta1", "ta", "நாளை காலை ஏழு மணிக்கு நாம் கிளம்பலாம்.", "We can leave tomorrow morning at seven."),
    ("ta2", "ta", "இந்த உணவு மிகவும் சுவையாக இருக்கிறது.", "This food is very tasty."),
    ("kn1", "kn", "ನನಗೆ ಒಂದು ಕಪ್ ಕಾಫಿ ಬೇಕು.", "I want a cup of coffee."),
    ("kn2", "kn", "ಬಸ್ ನಿಲ್ದಾಣ ಎಲ್ಲಿದೆ?", "Where is the bus stand?"),
    ("ml1", "ml", "എനിക്ക് കുറച്ച് മലയാളം അറിയാം.", "I know a little Malayalam."),
    ("ml2", "ml", "ഇന്ന് രാത്രി നമുക്ക് സിനിമയ്ക്ക് പോകാം.", "Let's go to a movie tonight."),
    ("en1", "en", "Can you hear me clearly?", "Can you hear me clearly?"),
]

# Four "people": different pitch/timbre made by resampling the TTS voice.
VOICES = {"A": 1.0, "B": 0.78, "C": 1.18, "D": 0.88}
CONVERSATION = [("A", "hi1"), ("B", "ta1"), ("C", "kn1"), ("D", "ml1"), ("A", "hi2"), ("B", "ta2"), ("C", "kn2"), ("D", "ml2")]


def synth(text, lang, factor, path):
    mp3 = path + ".mp3"
    try:
        gTTS(text, lang=lang).save(mp3)
    except Exception as e:  # Google TTS unreachable: fall back to robotic espeak-ng voices
        print(f"gTTS failed for {lang} ({e}); using espeak-ng")
        subprocess.run(["espeak-ng", "-v", lang, "-w", path + ".wav", text], check=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path + ".wav", "-ar", "24000", mp3], check=True)
        os.remove(path + ".wav")
    # asetrate shifts pitch + formants (a different-sounding person); atempo restores the speed.
    af = f"asetrate={int(24000 * factor)},aresample=24000,atempo={1 / factor:.4f}"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-af", af, "-ac", "1", "-ar", str(SR), "-f", "s16le", path + ".raw"], check=True)
    os.remove(mp3)
    pcm = np.fromfile(path + ".raw", dtype=np.int16).astype(np.float32)
    os.remove(path + ".raw")
    return pcm


def noise(n, rng):
    # Café-like background: pink noise + a low hum + random clatter.
    white = rng.standard_normal(n)
    pink = np.cumsum(white)
    pink -= np.convolve(pink, np.ones(400) / 400, mode="same")
    hum = np.sin(2 * np.pi * 100 * np.arange(n) / SR) * 0.3
    clatter = np.zeros(n)
    for _ in range(max(1, n // SR)):
        i = rng.integers(0, max(1, n - 800))
        clatter[i:i + 800] += rng.standard_normal(800) * np.exp(-np.arange(800) / 120)
    x = pink / (np.std(pink) + 1e-9) + hum + clatter
    return x / (np.std(x) + 1e-9)


def mix(speech, snr_db, rng):
    nz = noise(len(speech), rng)
    p_s = np.mean(speech ** 2) + 1e-9
    nz *= np.sqrt(p_s / (10 ** (snr_db / 10)))
    return speech + nz


def save(path, x):
    x = np.clip(x, -32000, 32000).astype(np.int16)
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(x.tobytes())


rng = np.random.default_rng(7)
pad = np.zeros(SR // 4, dtype=np.float32)
clips = {}
with open(os.path.join(OUT, "manifest.tsv"), "w", encoding="utf-8") as m:
    for sid, lang, text, ref in SENTENCES:
        x = synth(text, lang, 1.0, os.path.join(OUT, sid))
        clips[sid] = (lang, text, ref)
        clean = np.concatenate([pad, x, pad])
        save(os.path.join(OUT, f"{sid}_clean.wav"), clean)
        save(os.path.join(OUT, f"{sid}_noisy.wav"), mix(clean, 5, rng))
        m.write(f"{sid}_clean\t{lang}\t{sid}_clean.wav\t{ref}\t{text}\n")
        m.write(f"{sid}_noisy\t{lang}\t{sid}_noisy.wav\t{ref}\t{text}\n")

parts, truth = [np.zeros(SR, dtype=np.float32)], []
for who, sid in CONVERSATION:
    lang, text, ref = clips[sid]
    parts.append(synth(text, lang, VOICES[who], os.path.join(OUT, f"conv_{who}_{sid}")))
    parts.append(np.zeros(int(SR * 1.2), dtype=np.float32))
    truth.append(f"{who}\t{lang}\t{ref}")
conv = np.concatenate(parts)
save(os.path.join(OUT, "conversation.wav"), mix(conv, 15, rng))
with open(os.path.join(OUT, "conversation_truth.tsv"), "w", encoding="utf-8") as f:
    f.write("\n".join(truth) + "\n")
print(f"wrote {len(SENTENCES) * 2} clips + conversation ({len(conv) / SR:.1f} s) to {OUT}")
