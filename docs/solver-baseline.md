# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h11m | 6h30m | 6h27m | 6h43m |
| cycling | 45m | 1h20m | 1h22m | 2h18m |
| on-foot | 1h50m | 3h02m | 3h11m | 4h50m |
| bike mounts | 2 | 4.0 | 4.3 | 8 |
| dwell walk | 56m | 1h59m | 1h50m | 2h53m |
| idle | 0m | 0m | 2m | 9m |
| over-walk | 1h06m | 1h43m | 1h55m | 2h46m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h17m | 7h17m | 7h17m | 7h19m |
| cycling | 1h36m | 2h04m | 2h02m | 2h23m |
| on-foot | 1h02m | 1h47m | 1h37m | 2h12m |
| bike mounts | 5 | 7.0 | 6.8 | 8 |
| dwell walk | 2h58m | 3h15m | 3h24m | 3h51m |
| idle | 0m | 11m | 12m | 32m |
| over-walk | 37m | 1h46m | 1h45m | 3h10m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h27m | 7h28m | 7h35m | 7h50m |
| cycling | 2h05m | 2h07m | 2h18m | 2h40m |
| on-foot | 1h36m | 1h42m | 1h44m | 1h55m |
| bike mounts | 7 | 7.0 | 7.7 | 9 |
| dwell walk | 2h49m | 3h11m | 3h09m | 3h19m |
| idle | 12m | 26m | 22m | 30m |
| over-walk | 1h39m | 1h57m | 1h57m | 2h09m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h09m | 6h35m | 6h35m | 7h19m |
| cycling | 1h21m | 1h46m | 1h42m | 2h23m |
| on-foot | 1h17m | 2h02m | 2h06m | 2h45m |
| bike mounts | 4 | 6.0 | 5.4 | 8 |
| dwell walk | 2h03m | 2h36m | 2h37m | 3h37m |
| idle | 0m | 0m | 8m | 42m |
| over-walk | 46m | 1h59m | 1h58m | 3h05m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h15m | 7h15m | 7h15m | 7h15m |
| cycling | 2h04m | 2h05m | 2h05m | 2h05m |
| on-foot | 1h18m | 1h18m | 1h25m | 1h51m |
| bike mounts | 7 | 7.0 | 7.0 | 7 |
| dwell walk | 2h56m | 3h44m | 3h35m | 3h46m |
| idle | 4m | 6m | 9m | 22m |
| over-walk | 26m | 35m | 40m | 57m |

