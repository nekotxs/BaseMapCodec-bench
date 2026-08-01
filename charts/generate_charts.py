#!/usr/bin/env python3
"""
plot_mapcodec_benchmarks.py

Generates matplotlib charts for the fastmapcodec / mapcodecbench project:
JMH decode benchmarks, duplicate-key-cost benchmarks, and real-world call
frequency on a vanilla server vs a NeoForge modded server.

Input files (any number, any order, content auto-detected):
    - a decode_results.json-style file (JMH DecodeBenchmark entries)
    - a duplicate_results.json-style file (JMH DuplicateKeyCostBenchmark entries)
    - OR a single combined results.json containing both kinds of entries
      in one list (e.g. a full JMH run dumped to one file) -- entries are
      split by their "benchmark" name regardless of which file they came
      from, and multiple files' entries are merged, so any mix of
      one-file / two-file / many-file input works.

The real-world call-frequency data (from fastmapcodec-diagnostic.log
summaries, one run on a vanilla server and one on a NeoForge modpack
server) is HARDCODED below in VANILLA_HISTOGRAM / NEOFORGE_HISTOGRAM,
since it comes from one-off diagnostic runs rather than files that get
regenerated. Update those constants if you capture new diagnostic logs.

Usage:
    python plot_mapcodec_benchmarks.py results.json [-o OUTPUT_DIR]
    python plot_mapcodec_benchmarks.py decode_results.json duplicate_results.json [-o OUTPUT_DIR]

Output:
    Eight PNG files written to OUTPUT_DIR (default: ./plots):
        01_decode_full_loglog.png
        02_decode_no_vanilla.png
        03_decode_zoom_n_le_20.png
        04a_real_world_n_distribution_vanilla.png
        04b_real_world_n_distribution_neoforge.png
        05a_estimated_total_time_vanilla.png
        05b_estimated_total_time_neoforge.png
        06_duplicate_key_overhead_loglog.png

Note: the two servers (vanilla / neoforge) are never plotted together on
the same chart, since their call volumes differ by two orders of
magnitude and mixing them on one axis is misleading.
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

# --------------------------------------------------------------------------
# Hardcoded real-world call-frequency data
# Source: fastmapcodec-diagnostic.log summaries ("=== fastmapcodec summary ===")
#   - VANILLA:  vanilla server, no mods.        total calls = 2932
#   - NEOFORGE: NeoForge 1.21.1 modpack server. total calls = 183757
#
# Keys are either:
#   int               -> exact N observed  (N < 20 is logged exactly)
#   (low, high) tuple  -> power-of-two bucket for N >= 20 (bucket bounds inclusive)
# Values are call counts.
# --------------------------------------------------------------------------

VANILLA_HISTOGRAM = {
    0: 97,
    1: 1421,
    2: 1218,
    3: 83,
    4: 17,
    5: 2,
    7: 2,
    8: 67,
    9: 2,
    11: 8,
    15: 2,
    16: 1,
    17: 1,
    18: 1,
    (32, 63): 5,
    (128, 255): 5,
}
VANILLA_TOTAL_CALLS = 2932
VANILLA_FAILS = 0

NEOFORGE_HISTOGRAM = {
    0: 814,
    1: 155855,
    2: 24248,
    3: 976,
    4: 395,
    5: 327,
    6: 146,
    7: 141,
    8: 455,
    9: 44,
    10: 33,
    11: 32,
    12: 15,
    13: 23,
    14: 7,
    15: 16,
    16: 74,
    17: 1,
    18: 5,
    19: 8,
    (32, 63): 73,
    (64, 127): 51,
    (128, 255): 11,
    (512, 1023): 1,
    (16384, 32767): 6,
}
NEOFORGE_TOTAL_CALLS = 183757
NEOFORGE_FAILS = 9  # individual failed entries (7 calls had >=1 failure)

# --------------------------------------------------------------------------
# Styling
# --------------------------------------------------------------------------

IMPL_COLORS = {
    "builder": "#4C72B0",
    "builderSpecial": "#55A868",
    "openHashClean": "#C44E52",
    "openHashSpecial": "#DD8452",
    "vanilla": "#8172B2",
}
IMPL_LABELS = {
    "builder": "builder",
    "builderSpecial": "builderSpecial",
    "openHashClean": "openHashClean",
    "openHashSpecial": "openHashSpecial",
    "vanilla": "vanilla",
}
SERVER_COLOR = "#4C72B0"

plt.rcParams.update(
    {
        "figure.figsize": (10, 6),
        "figure.dpi": 120,
        "axes.grid": True,
        "grid.alpha": 0.3,
        "axes.axisbelow": True,
        "font.size": 11,
    }
)


# --------------------------------------------------------------------------
# Loading / parsing JMH result files
# --------------------------------------------------------------------------


def load_jmh_file(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def split_jmh_entries(data):
    """Split a JMH result list into (decode_entries, duplicate_entries) by
    inspecting each entry's own "benchmark" name. This works whether the
    file contains only DecodeBenchmark entries, only DuplicateKeyCostBenchmark
    entries, or both mixed together in a single combined results.json."""
    if not isinstance(data, list):
        return [], []
    decode_entries = [
        e for e in data if "DecodeBenchmark" in e.get("benchmark", "")
    ]
    duplicate_entries = [
        e for e in data if "DuplicateKeyCostBenchmark" in e.get("benchmark", "")
    ]
    return decode_entries, duplicate_entries


def decode_series(data, impl):
    """Return sorted (n_array, score_array) for one DecodeBenchmark implementation."""
    pts = []
    for entry in data:
        if not entry["benchmark"].endswith("." + impl):
            continue
        n = int(entry["params"]["n"])
        score = entry["primaryMetric"]["score"]
        pts.append((n, score))
    pts.sort()
    ns = np.array([p[0] for p in pts], dtype=float)
    scores = np.array([p[1] for p in pts], dtype=float)
    return ns, scores


def duplicate_series(data, impl, variant):
    """Return sorted (n_array, score_array) for one DuplicateKeyCostBenchmark impl+variant."""
    bench_suffix = f"{impl}{variant}"
    pts = []
    for entry in data:
        if not entry["benchmark"].endswith("." + bench_suffix):
            continue
        n = int(entry["params"]["n"])
        score = entry["primaryMetric"]["score"]
        pts.append((n, score))
    pts.sort()
    ns = np.array([p[0] for p in pts], dtype=float)
    scores = np.array([p[1] for p in pts], dtype=float)
    return ns, scores


def build_interpolator(ns, scores):
    """Log-log linear interpolator (with edge clamping) time(n) -> us/op estimate."""
    log_n = np.log10(np.maximum(ns, 0.5))
    log_s = np.log10(scores)
    order = np.argsort(log_n)
    log_n, log_s = log_n[order], log_s[order]

    def interp(n):
        x = np.log10(max(n, 0.5))
        y = np.interp(x, log_n, log_s)
        return 10**y

    return interp


# --------------------------------------------------------------------------
# Plot 1: full comparison, log-log
# --------------------------------------------------------------------------


def plot_decode_full(decode_data, out_dir):
    fig, ax = plt.subplots()
    for impl in ["builder", "builderSpecial", "openHashClean", "openHashSpecial", "vanilla"]:
        ns, scores = decode_series(decode_data, impl)
        if len(ns) == 0:
            continue
        ax.plot(
            ns,
            scores,
            marker="o",
            markersize=3,
            linewidth=1.6,
            label=IMPL_LABELS[impl],
            color=IMPL_COLORS[impl],
        )
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("N (map size)")
    ax.set_ylabel("decode time, us/op")
    ax.set_title("DecodeBenchmark: all implementations (log-log)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "01_decode_full_loglog.png")
    plt.close(fig)


# --------------------------------------------------------------------------
# Plot 2: without vanilla
# --------------------------------------------------------------------------


def plot_decode_no_vanilla(decode_data, out_dir):
    fig, ax = plt.subplots()
    for impl in ["builder", "builderSpecial", "openHashClean", "openHashSpecial"]:
        ns, scores = decode_series(decode_data, impl)
        if len(ns) == 0:
            continue
        ax.plot(
            ns,
            scores,
            marker="o",
            markersize=3,
            linewidth=1.8,
            label=IMPL_LABELS[impl],
            color=IMPL_COLORS[impl],
        )
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("N (map size)")
    ax.set_ylabel("decode time, us/op")
    ax.set_title("DecodeBenchmark: without vanilla (fast implementations only)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "02_decode_no_vanilla.png")
    plt.close(fig)


# --------------------------------------------------------------------------
# Plot 3: zoom on N <= 20 (where almost all real traffic lives)
# --------------------------------------------------------------------------


def plot_decode_zoom(decode_data, out_dir, n_max=20):
    fig, ax = plt.subplots()
    last_ns = None
    for impl in ["builder", "builderSpecial", "openHashClean", "openHashSpecial", "vanilla"]:
        ns, scores = decode_series(decode_data, impl)
        mask = ns <= n_max
        if not mask.any():
            continue
        last_ns = ns[mask]
        ax.plot(
            ns[mask],
            scores[mask],
            marker="o",
            markersize=4,
            linewidth=1.8,
            label=IMPL_LABELS[impl],
            color=IMPL_COLORS[impl],
        )
    ax.set_xlabel("N (map size)")
    ax.set_ylabel("decode time, us/op")
    ax.set_title(f"DecodeBenchmark: zoom on N \u2264 {n_max} (bulk of real traffic)")
    if last_ns is not None:
        ax.set_xticks(sorted(set(int(x) for x in last_ns)))
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "03_decode_zoom_n_le_20.png")
    plt.close(fig)


# --------------------------------------------------------------------------
# Plots 4a/4b: real-world N distribution, vanilla and neoforge as separate
# charts (different call volumes -- never mixed on one axis)
# --------------------------------------------------------------------------


def histogram_category_label(key):
    if isinstance(key, tuple):
        return f"{key[0]}-{key[1]}"
    return str(key)


def histogram_sort_key(key):
    return key[0] if isinstance(key, tuple) else key


def _plot_single_server_distribution(histogram, total_calls, server_name, filename, out_dir):
    keys = sorted(histogram, key=histogram_sort_key)
    labels = [histogram_category_label(k) for k in keys]
    counts = [histogram[k] for k in keys]

    fig, ax = plt.subplots(figsize=(12, 6))
    ax.bar(labels, counts, color=SERVER_COLOR)
    ax.set_yscale("log")
    ax.set_xlabel("N (exact value or bucket)")
    ax.set_ylabel("decode() call count (log)")
    ax.set_title(f"Real-world N distribution: {server_name} (total calls = {total_calls:,})")
    plt.setp(ax.get_xticklabels(), rotation=45, ha="right")
    fig.tight_layout()
    fig.savefig(out_dir / filename)
    plt.close(fig)


def plot_real_world_distribution_vanilla(out_dir):
    _plot_single_server_distribution(
        VANILLA_HISTOGRAM,
        VANILLA_TOTAL_CALLS,
        "vanilla server",
        "04a_real_world_n_distribution_vanilla.png",
        out_dir,
    )


def plot_real_world_distribution_neoforge(out_dir):
    _plot_single_server_distribution(
        NEOFORGE_HISTOGRAM,
        NEOFORGE_TOTAL_CALLS,
        "NeoForge modded server",
        "04b_real_world_n_distribution_neoforge.png",
        out_dir,
    )


# --------------------------------------------------------------------------
# Plots 5a/5b: cost comparison across implementations on each server's
# real-world workload (frequency-weighted using the JMH interpolators).
# One chart per server -- vanilla and neoforge are never mixed on one axis.
# --------------------------------------------------------------------------


def representative_n(key):
    if isinstance(key, tuple):
        return (key[0] * key[1]) ** 0.5  # geometric mean of bucket bounds
    return float(key)


def estimate_total_time_us(histogram, interpolators):
    totals = {}
    for impl, interp in interpolators.items():
        total = 0.0
        for key, count in histogram.items():
            n_rep = representative_n(key)
            total += interp(n_rep) * count
        totals[impl] = total
    return totals


def _plot_estimated_total_time_for_server(
    decode_data, histogram, total_calls, server_name, filename, out_dir
):
    """Compare the cost of the decode implementations themselves: total time
    each implementation would have spent processing one server's observed
    real-world call frequency."""
    impls = ["builder", "builderSpecial", "openHashClean", "openHashSpecial", "vanilla"]
    interpolators = {}
    for impl in impls:
        ns, scores = decode_series(decode_data, impl)
        if len(ns) == 0:
            continue
        interpolators[impl] = build_interpolator(ns, scores)

    totals_us = estimate_total_time_us(histogram, interpolators)
    totals_s = {k: v / 1e6 for k, v in totals_us.items()}

    fig, ax = plt.subplots()
    colors = [IMPL_COLORS[i] for i in impls]
    values = [totals_s.get(i, 0) for i in impls]
    bars = ax.bar([IMPL_LABELS[i] for i in impls], values, color=colors)
    ax.set_yscale("log")
    ax.set_ylabel("estimated total decode time, seconds (log)")
    ax.set_title(
        f"Implementation cost on {server_name} real-world workload\n"
        f"({total_calls:,} decode() calls; time from JMH, log-log interpolation)"
    )
    ax.bar_label(bars, fmt=lambda v: f"{v:,.3g}s", padding=3, fontsize=9)
    fig.tight_layout()
    fig.savefig(out_dir / filename)
    plt.close(fig)


def plot_estimated_total_time_vanilla(decode_data, out_dir):
    _plot_estimated_total_time_for_server(
        decode_data,
        VANILLA_HISTOGRAM,
        VANILLA_TOTAL_CALLS,
        "vanilla server",
        "05a_estimated_total_time_vanilla.png",
        out_dir,
    )


def plot_estimated_total_time_neoforge(decode_data, out_dir):
    _plot_estimated_total_time_for_server(
        decode_data,
        NEOFORGE_HISTOGRAM,
        NEOFORGE_TOTAL_CALLS,
        "NeoForge modded server",
        "05b_estimated_total_time_neoforge.png",
        out_dir,
    )


# --------------------------------------------------------------------------
# Plot 6: duplicate-key overhead, log-log, 4 lines
# (builder / openHashClean) x (no duplicate / duplicate at end)
# --------------------------------------------------------------------------


def plot_duplicate_overhead(duplicate_data, out_dir):
    """Log-log line chart with 4 series: {builder, openHashClean} x
    {NoDuplicate, WithDuplicateAtEnd}. Overhead is read as the vertical gap
    between the solid (NoDuplicate) and dashed (WithDuplicateAtEnd) line of
    the same color."""
    impls = ["builder", "openHashClean"]
    variants = [
        ("NoDuplicate", "-", "no duplicate"),
        ("WithDuplicateAtEnd", "--", "duplicate at end"),
    ]

    fig, ax = plt.subplots()
    for impl in impls:
        for variant_suffix, linestyle, variant_label in variants:
            ns, scores = duplicate_series(duplicate_data, impl, variant_suffix)
            if len(ns) == 0:
                continue
            ax.plot(
                ns,
                scores,
                linestyle=linestyle,
                marker="o",
                markersize=4,
                linewidth=1.8,
                color=IMPL_COLORS[impl],
                label=f"{IMPL_LABELS[impl]} ({variant_label})",
            )

    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("N")
    ax.set_ylabel("decode time, us/op")
    ax.set_title("Duplicate key cost: builder vs openHashClean (log-log)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "06_duplicate_key_overhead_loglog.png")
    plt.close(fig)


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="Generate mapcodecbench charts from JMH result files."
    )
    parser.add_argument(
        "files",
        nargs="+",
        help=(
            "One or more JMH result files, in any combination: a single "
            "combined results.json containing both DecodeBenchmark and "
            "DuplicateKeyCostBenchmark entries, or separate "
            "decode_results.json / duplicate_results.json files. Entries "
            "are matched by their own 'benchmark' name and merged across "
            "all given files."
        ),
    )
    parser.add_argument(
        "-o",
        "--output-dir",
        default="./plots",
        help="directory to write PNG charts into (default: ./plots)",
    )
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    decode_data = []
    duplicate_data = []

    for f in args.files:
        path = Path(f)
        if not path.exists():
            print(f"WARNING: file not found, skipping: {f}", file=sys.stderr)
            continue
        try:
            data = load_jmh_file(path)
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            print(f"WARNING: could not parse {f} as JSON, skipping ({e})", file=sys.stderr)
            continue

        decode_entries, duplicate_entries = split_jmh_entries(data)
        if not decode_entries and not duplicate_entries:
            print(f"WARNING: no recognizable JMH entries in {f}, skipping", file=sys.stderr)
            continue

        parts = []
        if decode_entries:
            decode_data.extend(decode_entries)
            parts.append(f"{len(decode_entries)} decode")
        if duplicate_entries:
            duplicate_data.extend(duplicate_entries)
            parts.append(f"{len(duplicate_entries)} duplicate")
        print(f"[+] {f} -> {', '.join(parts)} entries")

    decode_data = decode_data or None
    duplicate_data = duplicate_data or None

    if decode_data is None:
        print(
            "ERROR: no decode_results.json-like file provided; "
            "plots 01, 02, 03, 05a, 05b will be skipped.",
            file=sys.stderr,
        )
    if duplicate_data is None:
        print(
            "ERROR: no duplicate_results.json-like file provided; "
            "plot 06 will be skipped.",
            file=sys.stderr,
        )

    if decode_data is not None:
        plot_decode_full(decode_data, out_dir)
        plot_decode_no_vanilla(decode_data, out_dir)
        plot_decode_zoom(decode_data, out_dir)
        plot_estimated_total_time_vanilla(decode_data, out_dir)
        plot_estimated_total_time_neoforge(decode_data, out_dir)

    # real-world distributions use only the hardcoded histograms
    plot_real_world_distribution_vanilla(out_dir)
    plot_real_world_distribution_neoforge(out_dir)

    if duplicate_data is not None:
        plot_duplicate_overhead(duplicate_data, out_dir)

    print(f"\nDone. Charts written to: {out_dir.resolve()}")


if __name__ == "__main__":
    main()
