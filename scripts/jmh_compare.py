#!/usr/bin/env python3
"""
Compare two JMH JSON result files and report regressions.

Usage:
  python3 scripts/jmh_compare.py [--baseline <path>] [--threshold <pct>]

Exit codes:
  0 — no regressions
  1 — one or more benchmarks exceeded the threshold
  2 — input/usage error
"""

import argparse
import json
import sys
from pathlib import Path


RESULTS_DIR = Path("benchmark-results")


def find_two_latest_json() -> tuple[Path, Path]:
    """Return (baseline, current) — the two most recently modified JSON files."""
    files = sorted(RESULTS_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime)
    if len(files) < 2:
        print(
            f"Error: fewer than 2 JSON files in {RESULTS_DIR}/. "
            "Run benchmarks first or specify --baseline.",
            file=sys.stderr,
        )
        sys.exit(2)
    return files[-2], files[-1]


def load_json(path: Path) -> list[dict]:
    try:
        with path.open() as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"Error: file not found: {path}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError as exc:
        print(f"Error: malformed JSON in {path}: {exc}", file=sys.stderr)
        sys.exit(2)


def extract_scores(results: list[dict]) -> dict[str, float]:
    """Return {benchmark_name: mean_score} from a JMH JSON result list."""
    scores: dict[str, float] = {}
    for entry in results:
        name = entry.get("benchmark", "")
        params = entry.get("params") or {}
        if params:
            param_str = ":".join(str(v) for v in params.values())
            key = f"{name}:{param_str}"
        else:
            key = name
        primary = entry.get("primaryMetric", {})
        score = primary.get("score")
        if score is not None:
            scores[key] = float(score)
    return scores


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare two JMH JSON result files.")
    parser.add_argument("--baseline", type=Path, help="Baseline result file path.")
    parser.add_argument(
        "--threshold",
        type=float,
        default=10.0,
        metavar="PCT",
        help="Regression threshold in percent (default: 10).",
    )
    args = parser.parse_args()

    if args.baseline is not None:
        baseline_path = args.baseline
        if not baseline_path.exists():
            print(f"Error: baseline file not found: {baseline_path}", file=sys.stderr)
            sys.exit(2)
        # When --baseline is provided, current = most recently modified JSON
        files = sorted(RESULTS_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime)
        current_path = files[-1] if files else None
        if current_path is None or current_path == baseline_path:
            print("Error: could not determine a current result file.", file=sys.stderr)
            sys.exit(2)
    else:
        baseline_path, current_path = find_two_latest_json()

    baseline_data = load_json(baseline_path)
    current_data = load_json(current_path)

    baseline_scores = extract_scores(baseline_data)
    current_scores = extract_scores(current_data)

    common = sorted(set(baseline_scores) & set(current_scores))
    if not common:
        print(
            "Warning: No matching benchmarks found between the two result files.",
            file=sys.stderr,
        )
        sys.exit(2)

    header = f"{'Benchmark':<45} {'Baseline':>12} {'Current':>12} {'Delta':>9}    Status"
    print(header)
    print("-" * len(header))

    regressions = 0
    for name in common:
        base = baseline_scores[name]
        curr = current_scores[name]
        if base == 0:
            delta_pct = 0.0
        else:
            delta_pct = (curr - base) / base * 100.0

        if delta_pct > args.threshold:
            status = "REGRESSION"
            regressions += 1
        elif delta_pct < 0:
            status = "IMPROVED"
        else:
            status = "OK"

        sign = "+" if delta_pct >= 0 else ""
        print(
            f"{name:<45} {base:>10.1f} ns {curr:>10.1f} ns "
            f"{sign}{delta_pct:>7.1f}%    {status}"
        )

    print()
    if regressions:
        print(f"Result: {regressions} regression(s) found (threshold: {args.threshold:.0f}%)")
        sys.exit(1)
    else:
        print(f"Result: no regressions (threshold: {args.threshold:.0f}%)")


if __name__ == "__main__":
    main()
