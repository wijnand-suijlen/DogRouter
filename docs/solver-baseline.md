# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 8 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h11m | 6h30m | 6h27m | 6h33m |
| cycling | 45m | 45m | 49m | 1h04m |
| on-foot | 3h15m | 3h15m | 3h24m | 3h59m |
| bike mounts | 2 | 2.0 | 2.2 | 3 |
| dwell walk | 1h48m | 2h30m | 2h13m | 2h30m |
| idle | 0m | 0m | 0m | 0m |
| over-walk | 39m | 1h18m | 1h12m | 1h35m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h17m | 7h19m | 7h21m | 7h43m |
| cycling | 49m | 1h18m | 1h19m | 1h52m |
| on-foot | 2h12m | 2h41m | 2h46m | 3h43m |
| bike mounts | 2 | 4.0 | 4.0 | 6 |
| dwell walk | 2h34m | 3h04m | 3h03m | 3h39m |
| idle | 3m | 11m | 12m | 30m |
| over-walk | 9m | 1h06m | 1h11m | 2h03m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h45m | 7h45m | 7h48m | 7h57m |
| cycling | 1h04m | 1h04m | 1h09m | 1h34m |
| on-foot | 2h51m | 2h56m | 3h20m | 4h38m |
| bike mounts | 3 | 3.0 | 3.3 | 5 |
| dwell walk | 1h46m | 3h08m | 2h51m | 3h22m |
| idle | 26m | 26m | 26m | 29m |
| over-walk | 48m | 3h31m | 3h07m | 4h24m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h24m | 6h28m | 6h32m | 6h57m |
| cycling | 47m | 1h20m | 1h07m | 1h22m |
| on-foot | 2h19m | 2h25m | 2h44m | 3h21m |
| bike mounts | 2 | 4.0 | 3.2 | 4 |
| dwell walk | 2h15m | 2h49m | 2h40m | 3h06m |
| idle | 0m | 0m | 0m | 0m |
| over-walk | 1m | 36m | 54m | 2h10m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h15m | 7h17m | 7h20m | 7h44m |
| cycling | 1h23m | 1h51m | 1h50m | 2h05m |
| on-foot | 1h18m | 2h03m | 2h11m | 3h54m |
| bike mounts | 4 | 6.0 | 5.9 | 7 |
| dwell walk | 2h03m | 3h22m | 3h14m | 3h37m |
| idle | 0m | 0m | 3m | 23m |
| over-walk | 18m | 33m | 39m | 1h36m |

