# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h03m | 7h18m | 7h18m | 7h32m |
| cycling | 45m | 1h30m | 1h20m | 2h02m |
| on-foot | 1h49m | 2h32m | 3h03m | 4h27m |
| bike mounts | 2 | 5.0 | 4.3 | 7 |
| dwell walk | 1h45m | 2h40m | 2h41m | 3h40m |
| idle | 0m | 7m | 13m | 41m |
| over-walk | 1h23m | 1h52m | 2h00m | 2h50m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h17m | 7h17m | 7h18m | 7h19m |
| cycling | 1h51m | 2h06m | 2h08m | 2h23m |
| on-foot | 54m | 1h34m | 1h25m | 1h47m |
| bike mounts | 6 | 7.0 | 7.1 | 8 |
| dwell walk | 3h09m | 3h48m | 3h39m | 4h20m |
| idle | 0m | 2m | 5m | 17m |
| over-walk | 37m | 2h02m | 1h52m | 2h58m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h36m | 7h55m | 7h52m | 8h08m |
| cycling | 2h20m | 2h38m | 2h38m | 2h59m |
| on-foot | 45m | 59m | 1h02m | 1h50m |
| bike mounts | 8 | 9.0 | 9.0 | 10 |
| dwell walk | 3h20m | 3h50m | 3h50m | 4h04m |
| idle | 0m | 26m | 21m | 26m |
| over-walk | 43m | 1h26m | 1h38m | 3h23m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h09m | 7h25m | 7h12m | 8h17m |
| cycling | 1h20m | 2h06m | 1h54m | 2h20m |
| on-foot | 29m | 1h17m | 1h18m | 2h19m |
| bike mounts | 4 | 7.0 | 6.3 | 8 |
| dwell walk | 2h44m | 3h48m | 3h50m | 5h11m |
| idle | 0m | 0m | 9m | 1h16m |
| over-walk | 35m | 1h59m | 1h50m | 3h56m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h15m | 7h24m | 7h24m | 7h36m |
| cycling | 2h02m | 2h05m | 2h13m | 2h51m |
| on-foot | 38m | 1h15m | 1h09m | 1h18m |
| bike mounts | 7 | 7.0 | 7.5 | 10 |
| dwell walk | 3h43m | 3h47m | 3h53m | 4h10m |
| idle | 0m | 4m | 7m | 22m |
| over-walk | 21m | 42m | 49m | 1h21m |

