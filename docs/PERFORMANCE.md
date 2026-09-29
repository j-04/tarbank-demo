# Performance result

This result captures the standard local Docker workload defined in `load-test/k6.js`. It is a reproducible demo baseline, not a production capacity claim.

## Recorded run

- Timestamp: 2026-09-29 12:56:45 CEST (Europe/Belgrade)
- Application image: `tarbank-demo:section10`
- Application image ID: `sha256:3c159c6011122611d1922e9f86e49e5f3e1bdc302eba06c0ebd490687775eb47`
- Java: Eclipse Temurin 25.0.4.1
- Docker Engine: 27.3.1, 4 CPUs, 8,332,832,768 bytes memory
- PostgreSQL: 17.5
- Redis: 7.4.2
- k6: 2.3.0
- Failure simulator: disabled
- Rate-limit capacities and refill rates: 100,000 for this controlled workload
- Seed: 50 active customers, each with two EUR accounts and one USD account; each EUR source account was funded with a 1,000.0000 deposit through the public API

The scenario ran a separate 10-second read-only warm-up with 10 workers, allowed five seconds for warm-up workers to stop, and then held 50 active request workers without think-time for a 60-second measured interval. The measured request mix was 1 percent login, 75 percent account and history reads, and 24 percent money operations. Money operations used a fresh UUID v4 idempotency key per new request.

## Command

The application was started with the controlled-workload environment shown in the README, followed by:

```bash
export MANAGER_PASSWORD
./load-test/run.sh
```

## Results

| Endpoint group | p50 | p95 | p99 | Expected errors | Unexpected errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Login | 656.58 ms | 1,429.43 ms | 1,652.20 ms | 0 | 0 |
| Account list | 55.61 ms | 112.93 ms | 156.36 ms | 0 | 0 |
| Account detail | 61.46 ms | 117.46 ms | 162.86 ms | 0 | 0 |
| Paginated history | 64.16 ms | 132.77 ms | 192.52 ms | 0 | 0 |
| Deposit | 109.42 ms | 200.30 ms | 285.28 ms | 0 | 0 |
| Withdrawal | 117.24 ms | 218.90 ms | 285.37 ms | 0 | 0 |
| Same-currency transfer | 129.72 ms | 245.50 ms | 338.00 ms | 0 | 0 |

The measured interval completed 33,788 requests, approximately 563.13 measured requests per second. All 33,788 checks passed and the measured endpoint error rate was 0 percent. The run maintained 50 active workers throughout the measured interval.

Six endpoint groups passed the required one-second p99 threshold. Login did not: its p99 was 1,652.20 ms, so this run does not satisfy the overall performance acceptance criterion. The runner returned a nonzero status for that threshold failure. This is retained as the honest baseline rather than weakening password hashing or concealing login inside an aggregate.

The versioned runner explicitly exports median, p95, and p99 and then validates their presence for all seven endpoint groups. The raw aggregate HTTP metrics include setup and warm-up traffic; the table above uses measured-only custom endpoint trends.

The raw k6 summary is intentionally ignored at `load-test/results/latest.json`; it can be regenerated with the command above.
