# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h43m | 7h16m | 7h13m | 7h24m |
| cycling | 45m | 1h16m | 1h15m | 2h02m |
| on-foot | 1h49m | 3h06m | 3h14m | 4h27m |
| bike mounts | 2 | 4.0 | 3.9 | 7 |
| dwell walk | 1h45m | 2h26m | 2h30m | 3h31m |
| idle | 0m | 7m | 13m | 41m |
| over-walk | 1h23m | 1h50m | 1h59m | 2h50m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h17m | 7h17m | 7h18m | 7h19m |
| cycling | 1h36m | 2h04m | 2h04m | 2h23m |
| on-foot | 54m | 1h37m | 1h29m | 2h12m |
| bike mounts | 5 | 7.0 | 6.9 | 8 |
| dwell walk | 2h58m | 3h46m | 3h36m | 4h19m |
| idle | 0m | 4m | 7m | 20m |
| over-walk | 37m | 1h46m | 1h39m | 2h40m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h35m | 7h49m | 7h48m | 8h08m |
| cycling | 2h20m | 2h36m | 2h35m | 2h59m |
| on-foot | 45m | 1h05m | 1h19m | 1h55m |
| bike mounts | 8 | 9.0 | 8.8 | 10 |
| dwell walk | 2h49m | 3h44m | 3h31m | 4h04m |
| idle | 10m | 26m | 21m | 30m |
| over-walk | 43m | 1h23m | 1h35m | 3h23m |

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
| day length | 7h15m | 7h21m | 7h25m | 7h52m |
| cycling | 2h05m | 2h05m | 2h13m | 2h49m |
| on-foot | 26m | 1h15m | 1h08m | 1h18m |
| bike mounts | 7 | 7.0 | 7.5 | 10 |
| dwell walk | 3h37m | 3h46m | 3h53m | 4h29m |
| idle | 0m | 5m | 10m | 22m |
| over-walk | 11m | 39m | 47m | 1h24m |

