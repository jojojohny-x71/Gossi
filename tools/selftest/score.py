"""Turns EngineTest results into a readable report (stdout + GitHub job summary)."""
import os
import sys

from sacrebleu.metrics import CHRF

chrf = CHRF()
lines = []


def emit(s=""):
    lines.append(s)


for path in sys.argv[1:]:
    model = os.path.basename(path).replace("results_", "").replace(".tsv", "")
    rows = [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8") if l.strip()]
    load = next((r[1] for r in rows if r[0] == "# load_ms"), "?")
    sent = [r for r in rows if r[0] == "S"]
    conv = [r for r in rows if r[0] == "C"]
    emit(f"## Model: {model}  (load {load} ms)")
    emit()
    emit("| clip | lang | detected | time (s) / audio (s) | chrF | Gossi said | reference |")
    emit("|---|---|---|---|---|---|---|")
    by_lang = {}
    for _, cid, lang, det, ms, audio_ms, en, ref, *_ in sent:
        score = chrf.sentence_score(en, [ref]).score if en != "(dropped)" else 0.0
        cond = "noisy" if cid.endswith("noisy") else "clean"
        by_lang.setdefault((lang, cond), []).append((score, det == lang, int(ms), int(audio_ms)))
        emit(f"| {cid} | {lang} | {det}{'' if det == lang else ' ✗'} | {int(ms)/1000:.1f} / {int(audio_ms)/1000:.1f} | {score:.0f} | {en} | {ref} |")
    emit()
    emit("| lang | condition | avg chrF | language detected right | avg time per clip (s) |")
    emit("|---|---|---|---|---|")
    for (lang, cond), v in sorted(by_lang.items()):
        emit(f"| {lang} | {cond} | {sum(x[0] for x in v)/len(v):.0f} | {sum(x[1] for x in v)}/{len(v)} | {sum(x[2] for x in v)/len(v)/1000:.1f} |")
    emit()
    if conv:
        truth = [l.rstrip("\n").split("\t") for l in open(os.environ.get("TRUTH", "conversation_truth.tsv"), encoding="utf-8") if l.strip()]
        emit(f"Conversation: {len(conv)} utterances found (expected {len(truth)})")
        emit()
        emit("| # | true speaker | Gossi speaker | pitch Hz | lang (true→detected) | Gossi said | reference |")
        emit("|---|---|---|---|---|---|---|")
        for k, r in enumerate(conv):
            t = truth[k] if k < len(truth) else ["?", "?", "?"]
            emit(f"| {k+1} | {t[0]} | {r[2]} | {r[3]} | {t[1]}→{r[4]} | {r[6]} | {t[2]} |")
        emit()

report = "\n".join(lines)
print(report)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as f:
        f.write(report + "\n")
