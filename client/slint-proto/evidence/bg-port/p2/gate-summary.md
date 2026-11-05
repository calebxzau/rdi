# P2 gate evidence

| Time | Lake RGB mean absolute error | Cloud vertices | P1 max channel change | Depth p95 error |
|---|---:|---:|---:|---:|
|0.0s|0.780/255|19734|1/255|4.77e-07|
|15.0s|0.668/255|19734|1/255|2.98e-07|
|45.0s|0.958/255|19734|0/255|2.98e-07|

Cloud vertex bytes and thinned texture exactly match Kotlin. Authenticated memory: **passed**.

Matched sampler A/B: passed.

| Content | Native linear ms | Accepted nearest ms | Delta ms |
|---|---:|---:|---:|
|p1|4.054|4.289|+0.235|
|p2|5.753|6.013|+0.260|

| Case / phase | WS MiB | Private MiB | GPU dedicated / shared MiB | GPU total MiB | Allocated / reserved MiB |
|---|---:|---:|---:|---:|---:|
|static/loaded|198.45|262.13|81.54/128.30|209.84|60.875/87.000|
|static/foreground60s|198.23|261.79|81.54/128.30|209.84|61.188/87.000|
|static/minimized30s|200.75|228.43|45.35/82.62|127.96|18.688/52.000|
|static/restored10s|265.64|325.50|77.90/194.43|272.33|61.188/148.000|
|p1/loaded|381.59|427.85|67.40/286.32|353.72|115.250/167.000|
|p1/foreground60s|388.90|429.59|67.61/289.32|356.93|112.000/207.000|
|p1/minimized30s|388.91|407.81|47.34/241.51|288.85|72.688/188.000|
|p1/restored10s|389.03|407.91|47.53/289.32|336.85|112.000/188.000|
|p2/loaded|405.11|442.57|67.61/289.32|356.93|132.625/167.000|
|p2/foreground60s|417.59|453.83|67.74/291.38|359.12|129.438/207.000|
|p2/minimized30s|417.60|433.80|47.47/243.57|291.04|90.062/188.000|
|p2/restored10s|417.59|433.77|47.66/291.38|339.04|129.125/188.000|

| P2 minus accepted P1 / phase | WS MiB | Private MiB | GPU total MiB | Allocated MiB | Reserved MiB |
|---|---:|---:|---:|---:|---:|
|loaded|+23.53|+14.72|+3.20|+17.375|+0.000|
|foreground60s|+28.70|+24.24|+2.20|+17.438|+0.000|
|minimized30s|+28.69|+25.99|+2.20|+17.375|+0.000|
|restored10s|+28.56|+25.86|+2.20|+17.125|+0.000|

All cases loaded100 hosts/60 modpacks at2560x1440 scale2. API responses were200; error/warning logs empty. Visible runs used the accepted unfocused diagnostic policy. The P1/P2 glass screenshots were inspected for populated cards, avatars and live scene blur. Counter models must not be added together.
