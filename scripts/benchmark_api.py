#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import statistics
import time
import urllib.request


def percentile(samples: list[float], quantile: float) -> float:
    ordered = sorted(samples)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def request_once(url: str, timeout: float) -> float:
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=timeout) as response:
        response.read()
        if response.status >= 400:
            raise RuntimeError(f"HTTP {response.status}")
    return (time.perf_counter() - started) * 1000


def main() -> None:
    parser = argparse.ArgumentParser(description="Measure FinSight API latency.")
    parser.add_argument("--url", default="http://localhost:8080/actuator/health")
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()

    for _ in range(max(0, args.warmup)):
        request_once(args.url, args.timeout)
    samples = [
        request_once(args.url, args.timeout)
        for _ in range(max(1, args.requests))
    ]
    print(json.dumps({
        "url": args.url,
        "requests": len(samples),
        "minMillis": round(min(samples), 2),
        "meanMillis": round(statistics.fmean(samples), 2),
        "p50Millis": round(percentile(samples, 0.50), 2),
        "p95Millis": round(percentile(samples, 0.95), 2),
        "p99Millis": round(percentile(samples, 0.99), 2),
        "maxMillis": round(max(samples), 2),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
