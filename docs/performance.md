# Load test

Command:

```bash
k6 run perf/billforge-load.js
```

Scenarios: 10 virtual users for 30 seconds reading the public plans and health
endpoints. Thresholds: p95 under 500 ms and error rate under 1%.

## Reference run

Environment: local Spring Boot application with PostgreSQL and Redis on a 15 GB
machine, demo tenant seeded.

| Metric | Value |
|---|---|
| Requests | 1,200 |
| Throughput | 39.5 req/s |
| Checks passed | 100% (1,200 of 1,200) |
| Failed requests | 0.00% |
| Average latency | 2.8 ms |
| p90 | 5.1 ms |
| p95 | 7.3 ms |
| Max | 24.2 ms |

Both thresholds passed. The numbers describe a single-machine demo, not a production
capacity claim.
