#!/usr/bin/env python3
"""
Записывает короткий пример КАЖДОГО голоса Edge на его родном языке и кладёт
mp3 в app/src/main/assets/samples/<ShortName>.mp3 — приложение NovaEdgeTTS
проигрывает их кнопкой ▶ в списке голосов (без обращения к серверу).

Запуск (на ПК с интернетом, один раз):
    pip install edge-tts
    python tools/make_voice_samples.py
    python tools/make_voice_samples.py --lang af,de,en      # только эти языки
    python tools/make_voice_samples.py --texts my_texts.json  # свои фразы {"af": "...", ...}

Уже записанные файлы пропускаются — можно безопасно запускать повторно.
Размер: ~15–25 КБ на голос (≈ 5–8 МБ на все ~320 голосов).
Голоса языков, для которых нет фразы, пропускаются и перечисляются в конце
(для «Multilingual»-голосов используется английская фраза — они читают её нормально).
"""
import argparse, asyncio, json, sys
from pathlib import Path

import edge_tts

# Фразы — в tools/sample_texts.json (тот же список зашит в SampleTexts.kt).
TEXTS = json.loads((Path(__file__).resolve().parent / "sample_texts.json").read_text(encoding="utf-8"))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "app/src/main/assets/samples"))
    ap.add_argument("--lang", default="", help="коды языков через запятую (по умолчанию все)")
    ap.add_argument("--texts", default="", help="JSON {код_языка: фраза} — дополняет/заменяет встроенные")
    ap.add_argument("--parallel", type=int, default=4)
    a = ap.parse_args()

    texts = dict(TEXTS)
    if a.texts:
        texts.update(json.loads(Path(a.texts).read_text(encoding="utf-8")))
    only = {x.strip().lower() for x in a.lang.split(",") if x.strip()}
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)

    voices = await edge_tts.list_voices()
    print(f"Голосов в списке Edge: {len(voices)}")
    sem = asyncio.Semaphore(a.parallel)
    done = skipped = failed = 0
    no_text = {}

    async def one(v):
        nonlocal done, skipped, failed
        name = v["ShortName"]; lang = v["Locale"].split("-")[0].lower()
        if only and lang not in only:
            return
        target = out / f"{name}.mp3"
        if target.exists() and target.stat().st_size > 1000:
            skipped += 1; return
        text = texts.get(lang)
        if text is None and "Multilingual" in name:
            text = texts["en"]
        if text is None:
            no_text.setdefault(lang, []).append(name); return
        async with sem:
            for attempt in range(3):
                try:
                    await edge_tts.Communicate(text, name).save(str(target))
                    if target.stat().st_size > 1000:
                        done += 1; print("OK ", name); return
                    raise RuntimeError("пустой файл")
                except Exception as e:
                    if attempt == 2:
                        failed += 1; print("ERR", name, type(e).__name__, e)
                        target.unlink(missing_ok=True)
                    else:
                        await asyncio.sleep(1.5 * (attempt + 1))

    await asyncio.gather(*(one(v) for v in voices))
    print(f"\nГотово: {done}, пропущено (уже были): {skipped}, ошибок: {failed}")
    if no_text:
        print("Нет фразы для языков (добавьте через --texts): " + ", ".join(sorted(no_text)))
    print(f"Папка: {out}")


if __name__ == "__main__":
    asyncio.run(main())
