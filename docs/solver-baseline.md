# Solver baseline

Quality metrics across 10 seeds (0..9) per weekday, straight-line (haversine) distances, 60 restarts. Deterministic — regenerate with `run_baseline.sh`. Wall-clock solve time is reported to stdout only (non-deterministic).

Data: 10 dogs, 15 schedule rules. Settings: cycling 15.0km/h, walking 3.0km/h, bike overhead 10min, stop buffer 0min.

## Monday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 6h56m | 7h37m | 7h33m | 8h06m |
| cycling | 1h30m | 1h59m | 2h12m | 3h07m |
| on-foot | 8m | 1h00m | 1h02m | 1h59m |
| bike mounts | 5 | 7.0 | 7.7 | 11 |
| dwell walk | 3h30m | 4h30m | 4h12m | 4h45m |
| idle | 0m | 5m | 6m | 14m |
| over-walk | 19m | 3h25m | 2h49m | 4h51m |

## Tuesday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h39m | 7h44m | 7h44m | 7h52m |
| cycling | 1h45m | 2h01m | 2h02m | 2h19m |
| on-foot | 40m | 54m | 55m | 1h08m |
| bike mounts | 6 | 7.0 | 7.1 | 8 |
| dwell walk | 4h45m | 4h45m | 4h45m | 4h45m |
| idle | 0m | 0m | 0m | 2m |
| over-walk | 52m | 2h19m | 2h33m | 3h55m |

## Wednesday — 10 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 8h08m | 8h23m | 8h22m | 8h47m |
| cycling | 2h24m | 2h47m | 2h55m | 3h52m |
| on-foot | 0m | 45m | 39m | 58m |
| bike mounts | 8 | 9.5 | 10.1 | 14 |
| dwell walk | 4h30m | 4h45m | 4h45m | 5h00m |
| idle | 0m | 0m | 2m | 26m |
| over-walk | 15m | 1h32m | 1h40m | 2h39m |

## Thursday — 7 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h00m | 7h43m | 7h30m | 7h47m |
| cycling | 2h16m | 2h16m | 2h25m | 2h45m |
| on-foot | 14m | 26m | 22m | 26m |
| bike mounts | 8 | 8.0 | 8.6 | 10 |
| dwell walk | 4h00m | 5h00m | 4h42m | 5h00m |
| idle | 0m | 0m | 0m | 0m |
| over-walk | 35m | 1h49m | 1h27m | 1h49m |

## Friday — 8 walk options

| metric | min | median | mean | max |
|---|---|---|---|---|
| conflicts | 0 | 0.0 | 0.0 | 0 |
| day length | 7h33m | 7h33m | 7h40m | 7h51m |
| cycling | 2h02m | 2h02m | 2h12m | 2h48m |
| on-foot | 32m | 1h01m | 55m | 1h15m |
| bike mounts | 7 | 7.0 | 7.6 | 10 |
| dwell walk | 4h30m | 4h30m | 4h31m | 4h45m |
| idle | 0m | 0m | 0m | 2m |
| over-walk | 48m | 1h35m | 1h41m | 2h56m |

