#!/usr/bin/env python3
"""Render a plain-text script to an MP3 with Kokoro TTS, on CPU.

The bridge runs this on the machine that holds the Kokoro install; the phone only
plays the resulting MP3. Text is synthesized paragraph by paragraph so one long
script never overruns Kokoro's context, and a paragraph that is still too long is
first split at sentence ends, so every join between pieces of audio falls between
sentences and never inside one. Kokoro emits 24 kHz mono float audio, so a WAV is
written first and ffmpeg converts it, because writing MP3 directly is not
reliable across libsndfile builds.

Usage: render-kokoro.py <script.txt> <out.mp3> [voice]
"""

import os
import re
import subprocess
import sys
import time
import wave

import numpy as np
import torch
from kokoro import KPipeline

SAMPLE_RATE = 24000
PARAGRAPH_GAP_SECONDS = 0.45
SENTENCE_GAP_SECONDS = 0.20
# Well under Kokoro's 510-token context: a piece this long is a few sentences and
# leaves headroom for the phonemiser, which works in tokens rather than characters.
MAX_PIECE_CHARS = 900
DEFAULT_VOICE = "af_heart"
# A sentence ends at . ! or ? followed by whitespace. Abbreviations ("e.g. ") are
# left inside a piece, which is harmless: only the split points move, not the text.
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def paragraphs(text: str) -> list[str]:
    blocks = [block.strip() for block in text.split("\n\n")]
    return [block.replace("\n", " ") for block in blocks if block]


def split_long(block: str) -> list[str]:
    """A paragraph broken at sentence ends, so no piece is over the context limit.

    A single sentence longer than the limit is left whole: cutting mid-sentence is
    worse than letting Kokoro's own tokenizer handle it.
    """
    if len(block) <= MAX_PIECE_CHARS:
        return [block]
    pieces: list[str] = []
    current = ""
    for sentence in SENTENCE_END.split(block):
        if not current:
            current = sentence
        elif len(current) + 1 + len(sentence) <= MAX_PIECE_CHARS:
            current = f"{current} {sentence}"
        else:
            pieces.append(current)
            current = sentence
    if current:
        pieces.append(current)
    return pieces


def write_wav(path: str, samples: np.ndarray) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    script_path, out_path = sys.argv[1], sys.argv[2]
    voice = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else DEFAULT_VOICE

    try:
        text = open(script_path, encoding="utf-8").read()
    except OSError as error:
        print(f"render-kokoro: cannot read the script: {error}", file=sys.stderr)
        return 2
    blocks = paragraphs(text)
    if not blocks:
        print("render-kokoro: the script is empty", file=sys.stderr)
        return 2

    threads = int(os.environ.get("KOKORO_THREADS") or 0) or min(4, os.cpu_count() or 4)
    torch.set_num_threads(threads)

    started = time.time()
    try:
        pipeline = KPipeline(lang_code=voice[0], device="cpu")
    except Exception as error:
        print(f"render-kokoro: Kokoro could not start: {error}", file=sys.stderr)
        return 1
    paragraph_gap = np.zeros(int(PARAGRAPH_GAP_SECONDS * SAMPLE_RATE), dtype=np.float32)
    sentence_gap = np.zeros(int(SENTENCE_GAP_SECONDS * SAMPLE_RATE), dtype=np.float32)

    pieces: list[np.ndarray] = []
    spoken = 0.0
    for index, block in enumerate(blocks, start=1):
        sentences = split_long(block)
        paragraph: list[np.ndarray] = []
        for sentence in sentences:
            chunks = [audio.numpy() for _, _, audio in pipeline(sentence, voice=voice, speed=1.0)]
            paragraph.append(np.concatenate(chunks))
        joined = np.concatenate(
            [part for pair in zip(paragraph, [sentence_gap] * len(paragraph)) for part in pair][:-1]
        )
        pieces.append(joined)
        pieces.append(paragraph_gap)
        spoken += len(joined) / SAMPLE_RATE
        elapsed = time.time() - started
        rate = spoken / elapsed if elapsed else 0.0
        print(
            f"[{index}/{len(blocks)}] +{len(joined) / SAMPLE_RATE:6.1f}s  "
            f"total {spoken / 60:5.2f}min  elapsed {elapsed / 60:5.2f}min  "
            f"{rate:.2f}x realtime",
            flush=True,
        )

    wav_path = out_path.rsplit(".", 1)[0] + ".wav" if out_path.endswith(".mp3") else out_path
    write_wav(wav_path, np.concatenate(pieces))
    print(f"wrote {wav_path} ({spoken / 60:.2f} min of speech)", flush=True)

    if out_path.endswith(".mp3"):
        try:
            subprocess.run(
                [
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", wav_path,
                    "-codec:a", "libmp3lame", "-qscale:a", "4",
                    out_path,
                ],
                check=True,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            print(f"render-kokoro: ffmpeg could not convert the audio: {error}", file=sys.stderr)
            return 1
        size = os.path.getsize(out_path) / 1024 / 1024
        print(f"wrote {out_path} ({size:.1f} MB)", flush=True)
    print(f"done in {(time.time() - started) / 60:.2f} min", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
