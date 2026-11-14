"""슬리핑 앱 음원 생성 스크립트 (저작권 걱정 없는 직접 생성 음원).

생성 파일 (app/src/main/res/raw/)
- noise_brown.wav : 브라운 노이즈 30초, 끊김 없이 반복 재생되도록 앞뒤를 교차 페이드
- alarm_chime.wav : 부드러운 2음 차임 + 쉼, 반복 재생용

사용: python3 tools/generate_audio.py
"""

import pathlib
import wave

import numpy as np

RATE = 22_050
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def write_wav(path: pathlib.Path, samples: np.ndarray) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = (pcm * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def seamless_loop(x: np.ndarray, fade_s: float) -> np.ndarray:
    """끝부분 fade_s초를 시작부분에 교차 페이드해 반복 재생 시 이음매가 들리지 않게 한다."""
    n = int(fade_s * RATE)
    head, body, tail = x[:n], x[n:-n], x[-n:]
    t = np.linspace(0.0, 1.0, n)
    joined = tail * np.cos(t * np.pi / 2) + head * np.sin(t * np.pi / 2)
    return np.concatenate([joined, body])


def brown_noise(seconds: float, seed: int = 7) -> np.ndarray:
    rng = np.random.default_rng(seed)
    white = rng.standard_normal(int((seconds + 2) * RATE))
    brown = np.empty_like(white)
    acc = 0.0
    leak = 0.997  # 저주파가 무한히 쌓이지 않도록 약하게 새게 함
    for i, w in enumerate(white):
        acc = acc * leak + w * 0.02
        brown[i] = acc
    brown -= brown.mean()
    brown /= np.max(np.abs(brown))
    return seamless_loop(brown * 0.8, fade_s=2.0)


def alarm_chime() -> np.ndarray:
    def tone(freq: float, dur: float) -> np.ndarray:
        t = np.arange(int(dur * RATE)) / RATE
        env = np.exp(-t * 3.0) * np.minimum(1.0, t / 0.01)
        return (np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * freq * 2 * t)) * env

    silence = np.zeros(int(0.9 * RATE))
    gap = np.zeros(int(0.08 * RATE))
    out = np.concatenate([tone(880.0, 0.5), gap, tone(1318.5, 0.6), silence])
    return out / np.max(np.abs(out)) * 0.9


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    write_wav(OUT / "noise_brown.wav", brown_noise(30.0))
    write_wav(OUT / "alarm_chime.wav", alarm_chime())
    for f in sorted(OUT.glob("*.wav")):
        print(f"{f.name}: {f.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
