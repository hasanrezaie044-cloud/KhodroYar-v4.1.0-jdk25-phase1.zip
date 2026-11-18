#!/usr/bin/env python3
"""Compare offline-eval results.json files. Prints Markdown and writes summary.json.

A difference in accuracy is reported as meaningful only if it is >= 2 passed turns out of the
shared suite AND >= 15 percentage points; otherwise the report states that no meaningful
difference was demonstrated.
"""
import json, statistics, sys
from collections import defaultdict
from pathlib import Path


def agg(r):
    turns = [t for c in r["cases"] for t in c["turns"]]
    n_turns = sum(1 for c in r["cases"] for _ in (c["turns"] or [None]))
    passed = sum(all(t["checks"].values()) for t in turns)
    checks = [v for t in turns for v in t["checks"].values()]
    skills = defaultdict(lambda: [0, 0])
    for c in r["cases"]:
        for t in c["turns"] or [{"checks": {"error": False}}]:
            skills[c["skill"]][0] += all(t["checks"].values()); skills[c["skill"]][1] += 1
    steps = [s for t in turns for s in t["steps"]]
    img_steps = [s for c in r["cases"] if c.get("image") for t in c["turns"][:1] for s in t["steps"][:1]]
    med = lambda xs: round(statistics.median(xs), 2) if xs else None
    return {
        "backend": r["backend"], "turns_passed": passed, "turns_total": n_turns,
        "checks_passed": sum(checks), "checks_total": len(checks),
        "skills": {k: f"{v[0]}/{v[1]}" for k, v in sorted(skills.items())},
        "errors": [f"{c['id']}: {c['error']}" for c in r["cases"] if c.get("error")],
        "load_s": round(r["load_s"], 1), "peak_rss_mb": round(r["peak_rss_mb"]), "idle_rss_mb": round(r["idle_rss_mb"]),
        "median_ttft_s_text": med([s["ttft_s"] for c in r["cases"] if not c.get("image") for t in c["turns"] for s in t["steps"]]),
        "median_ttft_s_image": med([s["ttft_s"] for s in img_steps]),
        "median_image_encode_ms": med([s["image_encode_ms"] for s in img_steps if s.get("image_encode_ms")]),
        "median_gen_tok_s": med([s["gen_tok_s"] for s in steps if s.get("gen_tok_s")]),
        "median_prefill_tok_s": med([s["prompt_tok_s"] for s in steps if s.get("prompt_tok_s")]),
        "median_turn_s": med([sum(s["total_s"] for s in t["steps"]) for t in turns]),
        "model_calls": len(steps), "settings": r.get("settings"), "python_baseline_mb": r.get("python_baseline_mb"),
    }


def main(paths):
    if not paths:
        sys.exit("no results.json produced")
    rs = [json.loads(Path(p).read_text()) for p in paths]
    A = [agg(r) for r in rs]
    out = ["| metric | " + " | ".join(a["backend"] for a in A) + " |", "|---|" + "---|" * len(A)]
    rows = [("turns passed", lambda a: f"{a['turns_passed']}/{a['turns_total']}"),
            ("individual checks", lambda a: f"{a['checks_passed']}/{a['checks_total']}")]
    for skill in sorted({s for a in A for s in a["skills"]}):
        rows.append((f"skill: {skill}", lambda a, s=skill: a["skills"].get(s, "-")))
    rows += [("model load (s)", lambda a: a["load_s"]), ("peak RSS (MB)", lambda a: a["peak_rss_mb"]),
             ("RSS after load (MB)", lambda a: a["idle_rss_mb"]),
             ("median TTFT text step (s)", lambda a: a["median_ttft_s_text"]),
             ("median TTFT image step (s)", lambda a: a["median_ttft_s_image"]),
             ("median image encode (ms)", lambda a: a["median_image_encode_ms"]),
             ("median prefill (tok/s)", lambda a: a["median_prefill_tok_s"]),
             ("median generation (tok/s)", lambda a: a["median_gen_tok_s"]),
             ("median full turn (s)", lambda a: a["median_turn_s"]), ("model calls", lambda a: a["model_calls"])]
    out += [f"| {n} | " + " | ".join(str(f(a)) for a in A) + " |" for n, f in rows]
    verdict = []
    if len(A) >= 2:
        a, b = A[0], A[1]
        diff = a["turns_passed"] - b["turns_passed"]
        pp = 100 * diff / max(1, a["turns_total"])
        if abs(diff) >= 2 and abs(pp) >= 15:
            better = a if diff > 0 else b
            verdict.append(f"Meaningful accuracy difference: {better['backend']} passed {abs(diff)} more turns ({abs(pp):.0f} pp).")
        else:
            verdict.append(f"No meaningful accuracy difference demonstrated ({diff:+d} turns, {pp:+.0f} pp).")
    for a in A:
        if a["errors"]: verdict.append(f"{a['backend']} errors: " + "; ".join(a["errors"]))
    md = "\n".join(out + [""] + verdict)
    print(md)
    Path(paths[0]).parent.parent.joinpath("summary.json").write_text(json.dumps({"aggregates": A, "verdict": verdict}, ensure_ascii=False, indent=1))
    Path(paths[0]).parent.parent.joinpath("summary.md").write_text(md)


if __name__ == "__main__":
    main(sys.argv[1:])
