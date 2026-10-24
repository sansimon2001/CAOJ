# -*- coding: utf-8 -*-
"""
声纹算法离线验证：把 MfccScanner + Voiceprint 的算法在 Python 里逐行复刻，
用两把可区分的合成嗓音检验「能否把『我』认出来」以及阈值是否合理。

这不是验证 Kotlin 代码本身（编译已通过），而是验证**算法设计与阈值**成立。
"""
import math
import random

SR = 16000
FRAME = 400          # 25ms
HOP = 160            # 10ms
NFFT = 512
N_MEL = 26
N_MFCC = 12
DIM = 25
W_MFCC, W_DELTA, W_F0 = 1.0, 0.7, 1.6
F0_EVERY = 6
MIN_SCORE, MIN_MARGIN = 0.55, 0.08

random.seed(20261022)


# ---------------- 合成嗓音（源-滤波模型简化版：谐波 + 共振峰包络）----------------
def formant_gain(f, formants):
    g = 0.0
    for i, (fc, bw, amp) in enumerate(formants):
        g += amp / (1.0 + ((f - fc) / bw) ** 2)
    return g


def synth(dur, f0_base, formants, amp=0.25):
    n = int(dur * SR)
    out = [0.0] * n
    # 轻微的音高起伏与音节包络，避免过于“电子”
    f0 = f0_base
    t = 0
    while t < n:
        seg = min(int(0.18 * SR), n - t)
        vib = 1.0 + 0.02 * math.sin(2 * math.pi * 3.1 * t / SR)
        f0_cur = f0 * vib * random.uniform(0.985, 1.015)
        env_a = random.uniform(0.5, 1.0)
        for i in range(seg):
            tt = (t + i) / SR
            e = env_a * math.sin(math.pi * i / seg) ** 0.6   # 音节包络
            v = 0.0
            k = 1
            while True:
                fh = f0_cur * k
                if fh > 7500:
                    break
                v += formant_gain(fh, formants) * math.sin(2 * math.pi * fh * tt)
                k += 1
            out[t + i] = v * e * amp
        t += seg
        # 音节之间留一点空隙
        gap = int(random.uniform(0.03, 0.09) * SR)
        t += gap
    # 归一化到 -12dBFS 左右
    peak = max(1e-9, max(abs(x) for x in out))
    scale = 0.25 / peak
    return [x * scale for x in out]


# 两个说话人：A = 男声低沉，B = 女声明亮
SPK_A = (112.0, [(700, 130, 1.0), (1220, 160, 0.55), (2600, 260, 0.28)])
SPK_B = (218.0, [(880, 150, 1.0), (1900, 200, 0.60), (2950, 300, 0.30)])


# ---------------- 与 Kotlin 一致的信号处理 ----------------
def next_pow2(v):
    n = 1
    while n < v:
        n <<= 1
    return n


def fft(re, im):
    n = len(re)
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            re[i], re[j] = re[j], re[i]
            im[i], im[j] = im[j], im[i]
    ln = 2
    while ln <= n:
        ang = -2 * math.pi / ln
        wr, wi = math.cos(ang), math.sin(ang)
        half = ln // 2
        for k in range(0, n, ln):
            cr, ci = 1.0, 0.0
            for q in range(half):
                i1, i2 = k + q, k + q + half
                ur, ui = re[i1], im[i1]
                vr = re[i2] * cr - im[i2] * ci
                vi = re[i2] * ci + im[i2] * cr
                re[i1], im[i1] = ur + vr, ui + vi
                re[i2], im[i2] = ur - vr, ui - vi
                cr, ci = cr * wr - ci * wi, cr * wi + ci * wr
        ln <<= 1


def hz2mel(f):
    return 2595.0 * math.log10(1.0 + f / 700.0)


def mel2hz(m):
    return 700.0 * (10 ** (m / 2595.0) - 1.0)


def build_mel():
    fmin, fmax = 50.0, min(7000.0, SR / 2 * 0.95)
    pts = [hz2mel(fmin) + (hz2mel(fmax) - hz2mel(fmin)) * i / (N_MEL + 1) for i in range(N_MEL + 2)]
    hz = [mel2hz(p) for p in pts]
    bins = [min(NFFT // 2, max(0, int((NFFT + 1) * f / SR))) for f in hz]
    bank = []
    for m in range(N_MEL):
        l, c, r = bins[m], bins[m + 1], bins[m + 2]
        w = [0.0] * (NFFT // 2 + 1)
        for k in range(l, c):
            if c > l:
                w[k] = (k - l) / (c - l)
        for k in range(c, r):
            if r > c:
                w[k] = (r - k) / (r - c)
        bank.append(w)
    return bank


MEL = build_mel()
DCT = [[math.cos(math.pi * k * (n + 0.5) / N_MEL) for n in range(N_MEL)] for k in range(N_MFCC + 1)]
WIN = [0.54 - 0.46 * math.cos(2 * math.pi * i / (FRAME - 1)) for i in range(FRAME)]


def frame_features(sig, start):
    re = [0.0] * NFFT
    im = [0.0] * NFFT
    e = 0.0
    prev = sig[start]
    for i in range(FRAME):
        x = sig[start + i]
        e += x * x
        re[i] = (x - 0.97 * prev) * WIN[i]
        prev = x
    e /= FRAME
    db = -100.0 if e <= 1e-10 else 10 * math.log10(e)

    fft(re, im)
    half = NFFT // 2
    pow_ = [re[k] * re[k] + im[k] * im[k] for k in range(half + 1)]
    logmel = []
    for m in range(N_MEL):
        s = 0.0
        w = MEL[m]
        for k in range(len(w)):
            if w[k]:
                s += w[k] * pow_[k]
        logmel.append(math.log(s + 1e-10))
    ceps = []
    for k in range(N_MFCC + 1):
        ceps.append(sum(DCT[k][n] * logmel[n] for n in range(N_MEL)))
    return db, ceps


def est_f0(sig, start):
    minlag = max(2, int(SR / 400))
    maxlag = min(int(SR / 70), FRAME - 2)
    if maxlag <= minlag:
        return 0.0
    m = sum(sig[start:start + FRAME]) / FRAME
    x = [sig[start + i] - m for i in range(FRAME)]
    r0 = sum(v * v for v in x)
    if r0 <= 1e-9:
        return 0.0
    best_lag, best_r = 0, 0.0
    for lag in range(minlag, maxlag + 1):
        s = 0.0
        i = 0
        lim = FRAME - lag
        while i < lim:
            s += x[i] * x[i + lag]
            i += 2
        r = s / r0
        if r > best_r:
            best_r, best_lag = r, lag
    if best_lag <= 0 or best_r < 0.32:
        return 0.0
    hz = SR / best_lag
    return hz if 60 <= hz <= 500 else 0.0


def scan(sig, segments=None):
    """segments: [(label, start_ms, end_ms)] 或 None"""
    n_samples = len(sig)
    times, dbs, cepss, f0s = [], [], [], []
    k = 0
    start = 0
    while start + FRAME <= n_samples:
        db, ceps = frame_features(sig, start)
        center_ms = (start + FRAME // 2) * 1000 // SR
        times.append(center_ms)
        dbs.append(db)
        cepss.append(ceps)
        f0s.append(est_f0(sig, start) if k % F0_EVERY == 0 else 0.0)
        k += 1
        start += HOP

    n = len(dbs)
    if n < 10:
        return None
    sd = sorted(dbs)
    noise = sd[int((n - 1) * 0.15)]
    peak = sd[int((n - 1) * 0.95)]
    thr = noise + max(6.0, 0.30 * max(0.0, peak - noise))

    # 归属
    if not segments:
        bucket = [0] * n
        labels = ["__all__"]
    else:
        labels = []
        for s in segments:
            if s[0] not in labels:
                labels.append(s[0])
        bucket = [-1] * n
        si = 0
        segs = sorted(segments, key=lambda x: x[1])
        for i in range(n):
            t = times[i]
            while si < len(segs) and t > segs[si][2]:
                si += 1
            if si < len(segs) and t >= segs[si][1]:
                bucket[i] = labels.index(segs[si][0])

    kb = len(labels)
    acc = [0.0] * (kb * DIM)
    cnt = [0] * kb
    vcnt = [0] * kb
    f0sum = [0.0] * kb
    f0cnt = [0] * kb

    for i in range(n):
        bi = bucket[i]
        if bi < 0:
            continue
        cnt[bi] += 1
        if dbs[i] < thr:
            continue
        vcnt[bi] += 1
        base = bi * DIM
        for j in range(N_MFCC):
            acc[base + j] += cepss[i][j + 1]
        if i >= 2 and i + 2 < n:
            for j in range(N_MFCC):
                c1, c2 = cepss[i - 1][j + 1], cepss[i + 1][j + 1]
                c3, c4 = cepss[i - 2][j + 1], cepss[i + 2][j + 1]
                acc[base + N_MFCC + j] += (2.0 * (c2 - c1) + (c4 - c3)) / 10.0
        f0 = f0s[i]
        if 60 < f0 < 500:
            f0sum[bi] += math.log(f0 / 100.0)
            f0cnt[bi] += 1

    out = {}
    for bi in range(kb):
        c = max(1, vcnt[bi])
        base = bi * DIM
        raw = [0.0] * DIM
        for j in range(N_MFCC):
            raw[j] = acc[base + j] / c * W_MFCC
            raw[N_MFCC + j] = acc[base + N_MFCC + j] / c * W_DELTA
        raw[2 * N_MFCC] = W_F0 * (f0sum[bi] / f0cnt[bi]) if f0cnt[bi] else 0.0
        s2 = math.sqrt(sum(v * v for v in raw)) or 1.0
        out[labels[bi]] = {
            "vec": [v / s2 for v in raw],
            "frames": cnt[bi],
            "voiced": vcnt[bi],
        }
    return out, noise, peak, sum(1 for i in range(n) if dbs[i] >= thr) / n


# ---------------- 判定 ----------------
def match(tpl_vec, buckets):
    cands = [(lab, b["vec"]) for lab, b in buckets.items()
             if b["voiced"] >= 50 and lab != "__all__"]
    if not cands:
        return None
    allv = [tpl_vec] + [v for _, v in cands]
    mean = [sum(v[i] for v in allv) / len(allv) for i in range(DIM)]
    cen = lambda v: [v[i] - mean[i] for i in range(DIM)]
    tc = cen(tpl_vec)
    scores = []
    for lab, v in cands:
        cc = cen(v)
        scores.append((lab, sum(tc[i] * cc[i] for i in range(DIM))))
    scores.sort(key=lambda x: -x[1])
    best = scores[0]
    margin = best[1] - scores[1][1] if len(scores) > 1 else 1.0
    accepted = best[1] >= MIN_SCORE and (len(scores) == 1 or margin >= MIN_MARGIN)
    return best[0], best[1], margin, accepted, scores


def main():
    print("=" * 68)
    print("声纹算法离线验证（与 App 内算法同参数）")
    print("=" * 68)

    # 1) 录入：3 秒「我」的语音（另一次录音，条件略有差异）
    enroll = synth(3.0, SPK_A[0], SPK_A[1])
    r = scan(enroll, None)
    assert r, "录入扫描失败"
    buckets, noise, peak, vratio = r
    tpl = buckets["__all__"]["vec"]
    print("\n【录入】3.0s · 底噪 %.1fdB · 峰值 %.1fdB · 有效人声 %.0f%%" % (noise, peak, vratio * 100))

    # 2) 会话：A(2.0s) 静音 B(2.0s) 静音 A(1.0s)
    a1 = synth(2.0, SPK_A[0], SPK_A[1])
    b1 = synth(2.0, SPK_B[0], SPK_B[1])
    a2 = synth(1.0, SPK_A[0], SPK_A[1])
    sil = [0.0] * int(0.25 * SR)
    conv = a1 + sil + b1 + sil + a2
    segs = [
        ("说话人1", 0, 2000),
        ("说话人2", 2250, 4250),
        ("说话人1", 4500, 5500),
    ]
    r2 = scan(conv, segs)
    assert r2, "会话扫描失败"
    bks = r2[0]
    print("【会话】各说话人有效帧：" + "，".join(
        "%s=%d 帧" % (k, v["voiced"]) for k, v in bks.items()))

    m = match(tpl, bks)
    lab, score, margin, accepted, scores = m
    print("\n各说话人与「我」的中心化余弦相似度：")
    for k, s in scores:
        print("   %s : %+.4f" % (k, s))
    print("\n判定结果：我 = %s（相似度 %.3f，领先 %.3f）→ %s" % (
        lab, score, margin, "自动采用 ✅" if accepted else "把握不足，保留人工设置 ⚠️"))

    ok = (lab == "说话人1" and accepted)
    print("\n【结论】" + ("通过：正确认出『我』= 说话人1，且达到自动采用门槛" if ok
                        else "未通过：判定或阈值需要调整"))

    # 3) 反向验证：把「我」换成 B 的声纹，应认出说话人2
    enroll_b = synth(3.0, SPK_B[0], SPK_B[1])
    rb = scan(enroll_b, None)
    tpl_b = rb[0]["__all__"]["vec"]
    m2 = match(tpl_b, bks)
    print("\n【反向验证】用说话人2的声纹录入 → 判定：我 = %s（相似度 %.3f，领先 %.3f）→ %s" % (
        m2[0], m2[1], m2[2], "自动采用 ✅" if m2[3] else "把握不足 ⚠️"))
    ok2 = (m2[0] == "说话人2" and m2[3])
    print("   " + ("通过" if ok2 else "未通过"))

    # 4) 相似两人（同性别近似音色）作为已知边界演示
    spk_c = (150.0, [(760, 140, 1.0), (1300, 170, 0.55), (2650, 270, 0.28)])
    c1 = synth(2.0, SPK_A[0], SPK_A[1])
    c2 = synth(2.0, spk_c[0], spk_c[1])
    conv2 = c1 + sil + c2
    r3 = scan(conv2, [("说话人1", 0, 2000), ("说话人2", 2250, 4250)])
    m3 = match(tpl, r3[0])
    print("\n【边界演示】同性别、音色接近的两人（113Hz vs 150Hz）：")
    for k, s in m3[4]:
        print("   %s : %+.4f" % (k, s))
    print("   判定：我 = %s（相似度 %.3f，领先 %.3f）→ %s" % (
        m3[0], m3[1], m3[2], "自动采用" if m3[3] else "把握不足，保留人工设置（符合预期）"))

    return 0 if (ok and ok2) else 1


if __name__ == "__main__":
    raise SystemExit(main())
