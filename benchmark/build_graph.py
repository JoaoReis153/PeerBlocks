"""
build_graph.py — Visualise PeerBlocks benchmark results.

Reads benchmark_results.csv (produced by ./gradlew benchmark) and writes
benchmark_graph.png with two subplots:

  Top   : Transfer throughput (MB/s) vs number of seeders
  Bottom: Average download time (ms), split into transfer and hash verification

The top subplot shows a shaded min/max band plus the individual runs.

Usage:
    python build_graph.py [csv_path] [output_png] [setup]

Defaults:
    csv_path    = benchmark/benchmark_results.csv  (relative to script location)
    output_png  = benchmark/benchmark_graph.png    (relative to script location)
    setup       = "loopback"                       (shown in the title)
"""

import sys
import os
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker


# ── Config ─────────────────────────────────────────────────────────────────────

_HERE       = os.path.dirname(os.path.abspath(__file__))
CSV_PATH    = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, "benchmark_results.csv")
OUTPUT_PATH = sys.argv[2] if len(sys.argv) > 2 else os.path.join(_HERE, "benchmark_graph.png")
SETUP       = sys.argv[3] if len(sys.argv) > 3 else "loopback"

ACCENT   = "#2563EB"   # transfer mean line + dots
BAND     = "#93C5FD"   # min/max shading
SCATTER  = "#1E40AF"   # individual run dots
VERIFY   = "#F59E0B"   # verification phase
TOTAL    = "#6B7280"   # end-to-end line
GRID_CLR = "#E5E7EB"


# ── Load & clean ───────────────────────────────────────────────────────────────

if not os.path.exists(CSV_PATH):
    print(f"ERROR: {CSV_PATH} not found. Run the benchmark first.")
    sys.exit(1)

df = pd.read_csv(CSV_PATH)

# Drop rows where the benchmark failed (TIMEOUT / ERROR)
numeric_cols = [
    "transfer_ms", "verify_ms", "total_ms",
    "transfer_mb_per_sec", "total_mb_per_sec",
]
missing = [c for c in numeric_cols if c not in df.columns]
if missing:
    print(f"ERROR: {CSV_PATH} is missing columns {missing}. "
          "It was probably made by an older version; run the benchmark again.")
    sys.exit(1)

for col in numeric_cols:
    df[col] = pd.to_numeric(df[col], errors="coerce")
df = df.dropna(subset=numeric_cols)
df = df[df["transfer_ms"] > 0]

if df.empty:
    print("ERROR: no valid rows found in the CSV.")
    sys.exit(1)

file_mb = df["file_size_bytes"].iloc[0] / 1_048_576

# Per-seed aggregates
agg = df.groupby("seeds").agg(
    throughput_mean=("transfer_mb_per_sec", "mean"),
    throughput_min =("transfer_mb_per_sec", "min"),
    throughput_max =("transfer_mb_per_sec", "max"),
    total_mean     =("total_mb_per_sec",    "mean"),
    transfer_ms    =("transfer_ms",         "mean"),
    verify_ms      =("verify_ms",           "mean"),
).reset_index()

seeds      = agg["seeds"].values
iterations = df.groupby("seeds")["iteration"].count().max()


# ── Plot ───────────────────────────────────────────────────────────────────────

fig, (ax_tp, ax_dur) = plt.subplots(
    2, 1, figsize=(9, 7), sharex=True,
    gridspec_kw={"hspace": 0.08}
)

fig.suptitle(
    f"Download speed vs number of seeders  ({file_mb:.0f} MB file, {SETUP}, "
    f"{int(iterations)} runs each)",
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
    color=BAND, alpha=0.45, label="transfer min / max", zorder=1
)
ax_tp.plot(
    seeds, agg["throughput_mean"],
    color=ACCENT, linewidth=2, marker="o", markersize=6,
    label="transfer (mean)", zorder=3
)
ax_tp.plot(
    seeds, agg["total_mean"],
    color=TOTAL, linewidth=1.2, marker="o", markersize=3,
    label="end to end, incl. verification (mean)", zorder=2
)

# Individual run dots
ax_tp.scatter(
    df["seeds"], df["transfer_mb_per_sec"],
    color=SCATTER, s=18, alpha=0.35, zorder=2
)

ax_tp.set_ylabel("Throughput (MB/s)", fontsize=10, color="#374151")
ax_tp.yaxis.set_major_formatter(ticker.FormatStrFormatter("%.0f"))

# Ideal scaling reference line, from the 1-seed mean
baseline = agg.loc[agg["seeds"] == 1, "throughput_mean"].values
if len(baseline):
    ax_tp.plot(
        seeds, baseline[0] * seeds,
        color="#9CA3AF", linewidth=1.2, linestyle="--",
        label="ideal linear scaling", zorder=1
    )
ax_tp.legend(fontsize=8, framealpha=0.7, loc="upper left")

# Annotate peak
peak_row = agg.loc[agg["throughput_mean"].idxmax()]
ax_tp.annotate(
    f"  peak\n  {peak_row['throughput_mean']:.0f} MB/s\n  @ {int(peak_row['seeds'])} seeds",
    xy=(peak_row["seeds"], peak_row["throughput_mean"]),
    xytext=(peak_row["seeds"] + 0.3, peak_row["throughput_mean"] * 1.15),
    fontsize=8, color=ACCENT,
    arrowprops=dict(arrowstyle="-|>", color=ACCENT, lw=1),
)


# ── Duration subplot: transfer + verification, stacked ─────────────────────────

_style_ax(ax_dur)

ax_dur.bar(
    seeds, agg["transfer_ms"], width=0.6,
    color=ACCENT, alpha=0.85, label="transfer", zorder=3
)
ax_dur.bar(
    seeds, agg["verify_ms"], width=0.6, bottom=agg["transfer_ms"],
    color=VERIFY, alpha=0.9, label="hash verification", zorder=3
)

ax_dur.set_ylabel("Average time (ms)", fontsize=10, color="#374151")
ax_dur.set_xlabel("Number of seeder nodes", fontsize=10, color="#374151")
ax_dur.xaxis.set_major_locator(ticker.MultipleLocator(1))
ax_dur.yaxis.set_major_formatter(ticker.FormatStrFormatter("%.0f"))
ax_dur.legend(fontsize=8, framealpha=0.7, loc="upper right")


# ── Save ───────────────────────────────────────────────────────────────────────

plt.savefig(OUTPUT_PATH, dpi=150, bbox_inches="tight", facecolor="white")
print(f"Graph saved to {os.path.abspath(OUTPUT_PATH)}")
