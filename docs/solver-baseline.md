# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h30m | 6h30m | 6h34m | 6h54m |
| cycling | 45m | 45m | 50m | 1h18m |
| on-foot | 3h15m | 4h25m | 4h11m | 4h50m |
| bike mounts | 2 | 2.0 | 2.3 | 4 |
| dwell walk | 53m | 1h07m | 1h25m | 2h30m |
| idle | 0m | 5m | 7m | 26m |
| over-walk | 1h18m | 2h35m | 2h40m | 4h17m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h19m | 7h19m | 7h19m | 7h22m |
| cycling | 49m | 1h11m | 1h11m | 1h37m |
| on-foot | 2h36m | 2h49m | 2h49m | 3h07m |
| bike mounts | 2 | 3.5 | 3.5 | 5 |
| dwell walk | 2h23m | 3h04m | 3h04m | 3h17m |
| idle | 3m | 11m | 13m | 41m |
| over-walk | 22m | 1h58m | 1h39m | 3h14m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h36m | 7h45m | 7h48m | 8h11m |
| cycling | 1h04m | 1h14m | 1h24m | 1h54m |
| on-foot | 2h19m | 3h43m | 3h19m | 3h57m |
| bike mounts | 3 | 3.5 | 4.2 | 6 |
| dwell walk | 1h51m | 2h37m | 2h37m | 2h55m |
| idle | 26m | 26m | 26m | 28m |
| over-walk | 50m | 3h13m | 3h09m | 5h00m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h20m | 6h32m | 6h38m | 7h17m |
| cycling | 47m | 1h20m | 1h24m | 2h02m |
| on-foot | 1h07m | 2h19m | 2h17m | 2h45m |
| bike mounts | 2 | 4.0 | 4.3 | 7 |
| dwell walk | 2h28m | 2h49m | 2h53m | 3h33m |
| idle | 0m | 0m | 4m | 42m |
| over-walk | 32m | 1h07m | 1h35m | 3h05m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h15m | 7h17m | 7h18m | 7h21m |
| cycling | 1h51m | 1h51m | 1h55m | 2h05m |
| on-foot | 1h15m | 2h03m | 1h52m | 2h03m |
| bike mounts | 6 | 6.0 | 6.3 | 7 |
| dwell walk | 2h56m | 3h22m | 3h26m | 3h58m |
| idle | 0m | 0m | 3m | 22m |
| over-walk | 15m | 33m | 36m | 57m |

