# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h30m | 6h33m | 6h45m | 8h11m |
| cycling | 45m | 45m | 52m | 1h18m |
| on-foot | 3h14m | 3h48m | 3h45m | 4h25m |
| bike mounts | 2 | 2.0 | 2.4 | 4 |
| dwell walk | 53m | 1h50m | 2h00m | 3h53m |
| idle | 0m | 0m | 6m | 25m |
| over-walk | 54m | 1h26m | 1h55m | 4h17m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h19m | 7h19m | 7h19m | 7h20m |
| cycling | 49m | 1h11m | 1h10m | 1h21m |
| on-foot | 2h18m | 2h41m | 2h46m | 3h01m |
| bike mounts | 2 | 3.5 | 3.4 | 4 |
| dwell walk | 3h04m | 3h13m | 3h13m | 3h39m |
| idle | 3m | 8m | 9m | 28m |
| over-walk | 22m | 56m | 1h07m | 1h58m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h37m | 7h45m | 7h49m | 8h20m |
| cycling | 1h04m | 1h14m | 1h34m | 2h37m |
| on-foot | 1h51m | 2h51m | 2h50m | 4h00m |
| bike mounts | 3 | 3.5 | 4.9 | 9 |
| dwell walk | 2h37m | 2h58m | 2h59m | 3h22m |
| idle | 13m | 26m | 24m | 29m |
| over-walk | 48m | 1h48m | 2h04m | 3h31m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h20m | 6h28m | 6h38m | 7h05m |
| cycling | 47m | 1h20m | 1h11m | 2h02m |
| on-foot | 1h07m | 2h24m | 2h33m | 3h21m |
| bike mounts | 2 | 4.0 | 3.5 | 7 |
| dwell walk | 2h15m | 2h49m | 2h53m | 3h10m |
| idle | 0m | 0m | 0m | 0m |
| over-walk | 1m | 36m | 48m | 1h50m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h17m | 7h17m | 7h18m | 7h21m |
| cycling | 1h51m | 1h51m | 1h56m | 2h05m |
| on-foot | 1h13m | 2h03m | 1h48m | 2h03m |
| bike mounts | 6 | 6.0 | 6.3 | 7 |
| dwell walk | 3h22m | 3h22m | 3h33m | 3h58m |
| idle | 0m | 0m | 0m | 3m |
| over-walk | 2m | 33m | 24m | 33m |

