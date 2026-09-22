"""Merges LongSim shards and reports whether golems pile up anywhere over time.

    python dev-tools/longsim.py build/longsim/week-*.csv
"""
import glob
import sys
from collections import defaultdict

TICKS_PER_HOUR = 6000


def when(tick):
    hours = tick / TICKS_PER_HOUR
    return f"day {hours / 24:6.1f}" if hours >= 48 else f"hour {hours:5.1f}"


def region_name(key):
    region, plane = key
    x, y = (region >> 8) * 64 + 32, (region & 0xFF) * 64 + 32
    where = "underground" if y >= 4160 else "surface"
    return f"region {region:5d} ({x},{y}) plane {plane} {where}"


def main(patterns):
    files = sorted({f for p in patterns for f in glob.glob(p)})
    stats = defaultdict(lambda: defaultdict(float))
    maxima = defaultdict(lambda: defaultdict(float))
    regions = defaultdict(lambda: defaultdict(int))
    tastes = defaultdict(lambda: defaultdict(int))
    golems = 0
    for path in files:
        for line in open(path):
            if line.startswith("#"):
                golems += int(line.split("golems=")[1].split()[0])
                continue
            parts = line.strip().split(",")
            if parts[0] == "S":
                tick = int(parts[1])
                names = ["plans", "nothing", "exceptions", "voyages", "stuck", "underground", "upstairs", "sea"]
                for name, value in zip(names, parts[2:10]):
                    stats[tick][name] += float(value)
                maxima[tick]["stack"] = max(maxima[tick]["stack"], float(parts[10]))
                stats[tick]["plan_us_weighted"] += float(parts[11]) * float(parts[2])
                maxima[tick]["plan_ms"] = max(maxima[tick]["plan_ms"], float(parts[12]))
                maxima[tick]["heap"] = max(maxima[tick]["heap"], float(parts[13]))
                maxima[tick]["wall"] = max(maxima[tick]["wall"], float(parts[14]))
                stats[tick]["shards"] += 1
            elif parts[0] == "C":
                tick = int(parts[1])
                for i, value in enumerate(parts[2:]):
                    tastes[tick][i] += int(value)
            elif parts[0] == "R":
                tick = int(parts[1])
                for entry in parts[2:]:
                    region, plane, count = entry.split(":")
                    regions[tick][(int(region), int(plane))] += int(count)

    ticks = sorted(t for t in stats if stats[t]["shards"] == len(files))
    if not ticks:
        print("no complete snapshots yet")
        return
    print(f"{len(files)} shards, {golems} golems, {len(ticks)} complete snapshots, up to {when(ticks[-1]).strip()}")
    print()
    print("  when          stuck  underground upstairs  at sea  top region  most on 1 tile  plans   none  errors  plan us  slowest ms  heap MB")
    step = max(1, len(ticks) // 24)
    for t in ticks[::step] + ([ticks[-1]] if ticks[-1] not in ticks[::step] else []):
        s, m = stats[t], maxima[t]
        top = max(regions[t].values())
        print(f"  {when(t)}  {s['stuck']:6.0f}  {s['underground']:10.0f}  {s['upstairs']:8.0f}  {s['sea']:6.0f}  {top:10d}"
              f"  {m['stack']:14.0f}  {s['plans']:6.0f}  {s['nothing']:5.0f}  {s['exceptions']:6.0f}"
              f"  {s['plan_us_weighted'] / max(1, s['plans']):7.0f}  {m['plan_ms']:10.1f}  {m['heap']:7.0f}")

    # Where golems spend their time, averaged over the whole run.
    total = defaultdict(float)
    for t in ticks:
        for key, count in regions[t].items():
            total[key] += count / len(ticks)
    print()
    print("Most occupied regions, averaged over the run (share of all golems):")
    for key, avg in sorted(total.items(), key=lambda kv: -kv[1])[:12]:
        print(f"  {100 * avg / golems:5.1f}%  {region_name(key)}")
    print(f"  golems spread over {len(total)} regions in all")

    # Whether the golems that care where they are have got there. The last three columns are
    # every golem, wherever it is, which is what the first three have to beat to mean anything.
    shown = [t for t in ticks if tastes[t]]
    if shown:
        print()
        print("Golems with a taste in places, and how often they are in one (all golems, for scale):")
        print("  when           likes cold  likes heat    homesick  |  any in cold   any in heat    any home")
        step = max(1, len(shown) // 12)
        for t in shown[::step] + ([shown[-1]] if shown[-1] not in shown[::step] else []):
            c = tastes[t]

            def share(of, among):
                return f"{100 * c[of] / c[among]:5.1f}%" if c[among] else "    -"
            print(f"  {when(t)}       {share(1, 0)}      {share(3, 2)}      {share(5, 4)}  |"
                  f"       {share(7, 6)}        {share(8, 6)}      {share(9, 6)}")

    # A sink fills over time: compare the first and last quarter of the run.
    quarter = max(1, len(ticks) // 4)
    early, late = ticks[:quarter], ticks[-quarter:]
    growth = []
    for key in total:
        a = sum(regions[t].get(key, 0) for t in early) / len(early)
        b = sum(regions[t].get(key, 0) for t in late) / len(late)
        growth.append((b - a, a, b, key))
    growth.sort(reverse=True)
    print()
    print("Largest growth, first quarter of the run to the last (average golems there):")
    for delta, a, b, key in growth[:8]:
        print(f"  {a:7.1f} -> {b:7.1f}  ({100 * b / golems:4.1f}% of golems)  {region_name(key)}")


if __name__ == "__main__":
    main(sys.argv[1:] or ["build/longsim/week-*.csv"])
