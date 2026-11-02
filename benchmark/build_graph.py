"""
build_graph.py — Visualise PeerBlocks benchmark results.

Reads benchmark_results.csv (produced by Tests.Benchmark) and writes
benchmark_graph.png with two subplots:

  Top   : Average throughput (MB/s) vs number of seeders
  Bottom: Average download duration (ms) vs number of seeders

Both subplots show a shaded min/max band across iterations plus
individual data points so outliers are visible.

Usage:
    python build_graph.py [csv_path] [output_png]

Defaults:
    csv_path    = benchmark/benchmark_results.csv  (relative to script location)
    output_png  = benchmark/benchmark_graph.png    (relative to script location)
"""

import sys
import os
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np


# ── Config ─────────────────────────────────────────────────────────────────────

_HERE       = os.path.dirname(os.path.abspath(__file__))
CSV_PATH    = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, "benchmark_results.csv")
OUTPUT_PATH = sys.argv[2] if len(sys.argv) > 2 else os.path.join(_HERE, "benchmark_graph.png")

ACCENT   = "#2563EB"   # mean line + dots
BAND     = "#93C5FD"   # min/max shading
SCATTER  = "#1E40AF"   # individual run dots
GRID_CLR = "#E5E7EB"


# ── Load & clean ───────────────────────────────────────────────────────────────

if not os.path.exists(CSV_PATH):
    print(f"ERROR: {CSV_PATH} not found. Run the benchmark first.")
    sys.exit(1)

df = pd.read_csv(CSV_PATH)

# Drop rows where the benchmark failed (TIMEOUT / ERROR)
numeric_cols = ["duration_ms", "throughput_mb_per_sec"]
for col in numeric_cols:
    df[col] = pd.to_numeric(df[col], errors="coerce")
df = df.dropna(subset=numeric_cols)
df = df[df["duration_ms"] > 0]

if df.empty:
    print("ERROR: no valid rows found in the CSV.")
    sys.exit(1)

file_mb = df["file_size_bytes"].iloc[0] / 1_048_576

# Per-seed aggregates
agg = df.groupby("seeds").agg(
    throughput_mean=("throughput_mb_per_sec", "mean"),
    throughput_min =("throughput_mb_per_sec", "min"),
    throughput_max =("throughput_mb_per_sec", "max"),
    duration_mean  =("duration_ms",           "mean"),
    duration_min   =("duration_ms",           "min"),
    duration_max   =("duration_ms",           "max"),
).reset_index()

seeds      = agg["seeds"].values
iterations = df["iteration"].max()


# ── Plot ───────────────────────────────────────────────────────────────────────

fig, (ax_tp, ax_dur) = plt.subplots(
    2, 1, figsize=(9, 7), sharex=True,
    gridspec_kw={"hspace": 0.08}
)

fig.suptitle(
    f"Download speed vs number of seeders  ({file_mb:.0f} MB file, "
    f"{int(iterations)} iterations each)",
    fontsize=13, fontweight="bold", y=0.98
)


def _style_ax(ax):
    ax.set_facecolor("white")
    ax.grid(axis="y", color=GRID_CLR, linewidth=0.8, zorder=0)
    ax.grid(axis="x", color=GRID_CLR, linewidth=0.5, linestyle=":", zorder=0)
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color("#D1D5DB")
    ax.tick_params(colors="#374151", labelsize=9)


# ── Throughput subplot ─────────────────────────────────────────────────────────

_style_ax(ax_tp)

ax_tp.fill_between(
    seeds,
    agg["throughput_min"],
    agg["throughput_max"],
    color=BAND, alpha=0.45, label="min / max range", zorder=1
)
ax_tp.plot(
    seeds, agg["throughput_mean"],
    color=ACCENT, linewidth=2, marker="o", markersize=6,
    label="mean", zorder=3
)

# Individual run dots
for _, row in df.iterrows():
    ax_tp.scatter(
        row["seeds"], row["throughput_mb_per_sec"],
        color=SCATTER, s=18, alpha=0.35, zorder=2
    )

ax_tp.set_ylabel("Throughput (MB/s)", fontsize=10, color="#374151")
ax_tp.yaxis.set_major_formatter(ticker.FormatStrFormatter("%.0f"))
ax_tp.legend(fontsize=8, framealpha=0.7, loc="upper left")

# Annotate peak
peak_idx = agg["throughput_mean"].idxmax()
peak_row = agg.loc[peak_idx]
ax_tp.annotate(
    f"  peak\n  {peak_row['throughput_mean']:.0f} MB/s\n  @ {int(peak_row['seeds'])} seeds",
    xy=(peak_row["seeds"], peak_row["throughput_mean"]),
    xytext=(peak_row["seeds"] + 0.3, peak_row["throughput_mean"] * 0.92),
    fontsize=8, color=ACCENT,
    arrowprops=dict(arrowstyle="-|>", color=ACCENT, lw=1),
)


# ── Duration subplot ───────────────────────────────────────────────────────────

_style_ax(ax_dur)

ax_dur.fill_between(
    seeds,
    agg["duration_min"],
    agg["duration_max"],
    color=BAND, alpha=0.45, zorder=1
)
ax_dur.plot(
    seeds, agg["duration_mean"],
    color=ACCENT, linewidth=2, marker="o", markersize=6,
    zorder=3
)

for _, row in df.iterrows():
    ax_dur.scatter(
        row["seeds"], row["duration_ms"],
        color=SCATTER, s=18, alpha=0.35, zorder=2
    )

ax_dur.set_ylabel("Duration (ms)", fontsize=10, color="#374151")
ax_dur.set_xlabel("Number of seeder nodes", fontsize=10, color="#374151")
ax_dur.xaxis.set_major_locator(ticker.MultipleLocator(1))
ax_dur.yaxis.set_major_formatter(ticker.FormatStrFormatter("%.0f"))


# ── Ideal scaling reference line (throughput) ──────────────────────────────────

baseline = agg.loc[agg["seeds"] == 1, "throughput_mean"].values
if len(baseline):
    ideal = baseline[0] * seeds
    ax_tp.plot(
        seeds, ideal,
        color="#9CA3AF", linewidth=1.2, linestyle="--",
        label="ideal linear scaling", zorder=1
    )
    ax_tp.legend(fontsize=8, framealpha=0.7, loc="upper left")


# ── Save ───────────────────────────────────────────────────────────────────────

plt.savefig(OUTPUT_PATH, dpi=150, bbox_inches="tight", facecolor="white")
print(f"Graph saved to {os.path.abspath(OUTPUT_PATH)}")
