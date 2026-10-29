# Retech Bank — Digital Banking System

A money-transfer and fraud-detection platform built as **Spring Boot microservices** with a **SAGA-orchestrated
transfer flow**, **Kafka** events, **Redis** and **PostgreSQL** — plus:

- a **Rust fraud risk engine** (Axum + Tokio) that makes every fraud decision,
- the same Rust rules compiled to **WebAssembly** for the **Risk Lab** simulator,
- the **Retech Bank mobile app** (Expo / React Native) where customers sign up, send money **by IBAN** and confirm
  unusual transfers with a **one-time code** sent by email (and optionally SMS).

Amounts are Turkish lira (₺1.234,56). All money is demo money: there is no payment provider.

---

## Contents

- [Architecture](#architecture)
- [Services and ports](#services-and-ports)
- [Quick start](#quick-start)
- [Using the app](#using-the-app)
- [How a transfer works](#how-a-transfer-works)
- [Fraud detection](#fraud-detection)
- [Authentication and security](#authentication-and-security)
- [IBANs](#ibans)
- [Notifications and verification codes](#notifications-and-verification-codes)
- [Data and persistence](#data-and-persistence)
- [Risk Lab (WebAssembly)](#risk-lab-webassembly)
- [API reference](#api-reference)
- [Configuration](#configuration)
- [Repository layout](#repository-layout)
- [Development](#development)
- [Testing](#testing)
- [Benchmarks](#benchmarks)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)

---

## Architecture

```
 Retech Bank app (Expo)                       Risk Lab (browser, WebAssembly — simulation only)
        │ HTTPS/JSON + JWT
        ▼
 ┌──────────────┐   validates JWT, routes, rate-limits (Redis)
 │ api-gateway  │──────────────┬───────────────────────────┬─────────────────────────┐
 └──────────────┘              ▼                           ▼                         ▼
                        ┌──────────────┐           ┌──────────────┐         ┌────────────────────┐
                        │ user-service │──REST────▶│ account-     │◀──REST──│ transaction-service│
                        │ sign-up/login│ open acct │ service      │ debit / │ SAGA orchestrator  │
                        │ JWT, BCrypt  │           │ ledger, IBAN │ refund  │ OTP (Redis)        │
                        └──────┬───────┘           └──────┬───────┘         └─────────┬──────────┘
                           user_db                   account_db                transaction_db
                                                          ▲                           │
                                     Kafka ───────────────┼───────────────────────────┤
                                                          │                           ▼
 ┌──────────────────────┐  REST   ┌─────────────────────────┐   Kafka   ┌──────────────────────┐
 │ risk-engine (Rust)   │◀────────│ fraud-detection-service │◀──────────│ transaction.initiated│
 │ Axum + Tokio         │ decision│ velocity / average      │           └──────────────────────┘
 └──────────────────────┘         │ (Redis), Java fallback  │
                                  └─────────────────────────┘
 ┌──────────────────────┐
 │ notification-service │ ◀── Kafka events ──▶ email (Mailpit / Resend), SMS (Twilio)
 └──────────────────────┘
```

---

## Services and ports

| Service | Port | Tech | Storage |
|---|---|---|---|
| **api-gateway** | **8080** (the only public port) | Spring Cloud Gateway — JWT validation, routing, rate limiting, CORS | Redis |
| user-service | 8087 | Spring Boot — sign-up, log-in, JWTs, BCrypt passwords | PostgreSQL `user_db` |
| account-service | 8081 | Spring Boot — accounts, balances, IBANs, idempotent ledger | PostgreSQL `account_db` |
| transaction-service | 8082 | Spring Boot — SAGA orchestrator, OTP verification, recovery | PostgreSQL `transaction_db`, Redis |
| fraud-detection-service | 8084 | Spring Boot — collects facts, asks the risk engine | Redis |
| notification-service | 8085 | Spring Boot — email (SMTP) and SMS (Twilio) | — |
| **risk-engine** | 8086 | **Rust, Axum + Tokio** — fraud decisions | stateless |
| **risk-dashboard** (Risk Lab) | 3000 | **React + TypeScript + WebAssembly** | — |
| **mobile** | 8088 (Expo dev server) | **Expo SDK 58, React Native, Expo Router** | device keychain |
| mailpit | 8025 (inbox), 1025 (SMTP) | Local catch-all email inbox | in-container |
| postgres | **5433** on the host | PostgreSQL 16 | Docker volume `postgres-data` |
| redis / kafka / zookeeper | 6379 / 9092 / — | Redis 7, Confluent Kafka 7.4 | — |

Everything except the gateway is bound to `127.0.0.1`, so a phone on your Wi-Fi can reach only the gateway.
PostgreSQL uses host port **5433** because a native PostgreSQL usually owns 5432 on Windows.

---

## Quick start

**Requirements:** Docker Desktop and Node.js 20+. Nothing else — every image builds its own sources (Maven, Rust,
Node all run inside Docker).

```powershell
copy .env.example .env          # first time only — then put a long random value in JWT_SECRET
docker compose up -d --build    # first build takes a few minutes (Maven + Rust)
docker compose ps               # wait until the services report "healthy"
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1   # 41 end-to-end checks

cd mobile
npm install
npm start                       # Expo on port 8088
```

| Open | |
|---|---|
| Retech Bank app | **Expo Go** on your phone (same Wi-Fi as the PC — scan the QR code) or the **Android emulator** (press `a`) |
| <http://localhost:8025> | **Mailpit** — every email the bank sends, including verification codes |
| <http://localhost:3000> | **Risk Lab** — fraud-rule simulator |

Stop with `docker compose down`. Your data is kept (see [Data and persistence](#data-and-persistence)).

---

## Using the app

1. **Open an account** — name, email, phone, password and an opening deposit (demo money). The account opens
   instantly with a Turkish IBAN, shown on the home screen.
2. **Open a second account** (another phone, the emulator, or log out and sign up again) and note its IBAN.
3. **Send money** — type the recipient's IBAN. The app checks it as you go, then shows the recipient as "Bora D."
   before you confirm the amount.
4. **Unusual transfers pause** — e.g. more than 90% of your balance, or more than 3× your usual amount.
   The app shows "Confirm it's you" with a 5-minute countdown.
5. **Get the code** from <http://localhost:8025> (on the PC) and type it in. Or tap **I didn't make this transfer**.

| What you do | Result |
|---|---|
| Correct code | Transfer completes; both people get an email |
| Wrong code | Transfer cancelled, money refunded, **account blocked** |
| No code within 5 minutes | Transfer cancelled, money refunded |
| "I didn't make this transfer" | Transfer cancelled, money refunded, **account blocked** |

---

## How a transfer works

transaction-service orchestrates a **SAGA**: each step is saved before its side effects run, every step that moves
money is **idempotent**, and failed steps are compensated.

```
POST /transactions/transfer  (receiverIban)
 │ check the sender account belongs to the user; resolve IBAN → account
 ├─ save PENDING
 ├─ account-service: deduct sender            (reference = transaction id → never twice)
 ├─ save PROCESSING (+ balance before)
 └─ Kafka transaction.initiated ─▶ fraud-detection-service ─▶ Rust risk engine
        ALLOW  ─▶ fraud.check.clean     ─▶ COMPLETED ─▶ transaction.completed ─▶ receiver credited
        REVIEW ─▶ verification.required ─▶ PENDING_VERIFICATION, code in Redis (5 min)
                     correct code ─▶ COMPLETED
                     wrong code / "not me" ─▶ COMPENSATING ─▶ refund + block ─▶ FLAGGED
                     expired ─▶ COMPENSATING ─▶ refund ─▶ FLAGGED
        BLOCK  ─▶ fraud.check.blocked   ─▶ COMPENSATING ─▶ refund + block ─▶ FLAGGED
```

| Status | Meaning |
|---|---|
| `PENDING` | Saved; sender not yet debited |
| `PROCESSING` | Sender debited; waiting for the fraud check |
| `PENDING_VERIFICATION` | Paused; waiting for the customer's code |
| `COMPLETED` | Done; receiver credited |
| `FAILED` | Rejected before any money moved (e.g. insufficient balance) |
| `COMPENSATING` | Being cancelled; refund in progress (retried until it succeeds) |
| `FLAGGED` | Cancelled and refunded |

**Financial safety**

- Money moves only through operations keyed by transaction id in an `account_operations` ledger: a retried debit,
  credit or refund never applies twice, and a refund can only return what was actually debited.
- Balance changes take a row lock (`SELECT … FOR UPDATE`), so concurrent transfers can't overdraw an account.
- Transfers use optimistic locking (`@Version`): two simultaneous OTP submissions can't both win.
- `TransactionRecoveryScheduler` (every 30 s) finishes or compensates interrupted transfers: stuck `PENDING`
  (> 2 min), lost fraud verdicts (> 5 min), unfinished refunds, expired codes, unacknowledged completion events.
- Kafka listeners retry 3× and then dead-letter to `<topic>.DLT`; duplicate events are no-ops.

**Kafka topics**

| Topic | From → to |
|---|---|
| `transaction.initiated` | transaction → fraud-detection |
| `fraud.check.clean` / `verification.required` / `fraud.check.blocked` | fraud-detection → transaction |
| `transaction.otp.generated` | transaction → notification |
| `transaction.completed` | transaction → account (credit receiver), notification |
| `transaction.refunded` | transaction → notification |
| `fraud.detected` | transaction → account (block), notification |

---

## Fraud detection

fraud-detection-service gathers the facts for each transfer and asks the **Rust risk engine** for a decision:

- **transfers in the last 60 s** (Redis counter, `fraud:velocity:{account}`),
- **usual amount** — a running average (Redis, `fraud:avg_amount:{account}`),
- **balance before the transfer** (carried in the event).

| Rule | Fires when |
|---|---|
| Speed | more than **5** transfers in 60 seconds |
| Unusual amount | amount > **3×** the usual amount (needs earlier transfers) |
| Share of the balance | amount > **90%** of the balance before the transfer |

| Decision | When | What happens |
|---|---|---|
| **ALLOW** | no rule fires | transfer completes |
| **REVIEW** | one or two rules fire | money held, verification code by email |
| **BLOCK** | all three rules fire, or more than 10 transfers a minute | refund + account blocked |

- If the Rust engine is unreachable, the Java `LocalRiskEvaluator` (an identical port of the rules) decides, so
  transfers are never left unchecked. Tests keep the two implementations in step.
- Each verdict is stored in Redis (`fraud:processed:{transactionId}`, 24 h), so a redelivered event replays the same
  verdict instead of being counted twice.
- Thresholds live in `fraud.*` (fraud-detection-service `application.yml`) and `RISK_*` (risk-engine in
  `docker-compose.yml`) — keep them in sync.

---

## Authentication and security

- **user-service** stores BCrypt password hashes and issues **HS256 JWTs** (60 minutes, `sub` = user id).
  `JWT_SECRET` in `.env` is shared with the gateway.
- **api-gateway** is the only entry point. It validates every token (signature, expiry, issuer) except on sign-up and
  log-in, strips any client-sent `X-User-Id`, and forwards the verified one.
- Users can only see and act on their **own** account, history and transfers: another user's data answers `404`,
  sending from someone else's account answers `403`.
- Internal endpoints (`deduct`, `credit`, `reverse`, `block`, account creation) are **not routed** by the gateway.
- Rate limits per user / per IP; sign-up and log-in are limited more tightly against password guessing.
- Verification codes come from `SecureRandom`, are compared in constant time, expire after 5 minutes and are never
  written to logs.
- The app keeps its session in the device keychain (`expo-secure-store`).

---

## IBANs

Customers send money to an **IBAN**. Each account gets a Turkish IBAN when it opens:

```
TR kk 00999 0 0000AAAAAAAAAAAA      (26 characters)
   │   │    │  └─ 12-digit account number, zero-padded to 16
   │   │    └─ reserve digit
   │   └─ Retech Bank's (fictional) bank code — bank.iban.bank-code
   └─ ISO 7064 MOD 97-10 check digits
```

- `GET /accounts/iban/{iban}` accepts spaces and lower case; `400` = typo (checksum), `422` = another bank's IBAN,
  `404` = no such account. It reveals only an abbreviated name ("Bora D.").
- The transfer request takes `receiverIban`; internally the SAGA still works with account numbers.
- Accounts opened before IBANs existed get theirs when account-service starts.

---

## Notifications and verification codes

notification-service looks up each customer's email and phone in account-service and sends:

| Event | Email | SMS |
|---|---|---|
| Verification code | ✓ (code only — **no links**; confirm in the app) | ✓ |
| Debit / credit alert | ✓ | — |
| Refund processed | ✓ | — |
| Account blocked | ✓ | ✓ |

- **Mailpit (default):** a catch-all inbox at <http://localhost:8025> that receives **every** email, for every user.
  Search `to:someone@example.com` to filter. Messages are cleared when the container is recreated.
- **Resend (real email):** uncomment the `MAIL_*` lines in `.env` and restart notification-service. Until you verify
  a domain in Resend, it only delivers to your own Resend account address (`MAIL_FROM=onboarding@resend.dev`).
- **Twilio SMS (optional):** set `TWILIO_*` in `.env`. Only phones in international format (`+905551234567`) get SMS;
  trial accounts deliver only to numbers verified in the Twilio console.

---

## Data and persistence

**PostgreSQL** runs in Docker (not your desktop PostgreSQL). Data lives in the Docker volume `postgres-data`, so it
survives `docker compose stop` / `down`, restarting Docker Desktop and rebooting. It is deleted only by
`docker compose down -v`, `docker volume rm banking_postgres-data`, or Docker Desktop's "Clean / Purge data".

| Database | Tables |
|---|---|
| `user_db` | `users` |
| `account_db` | `accounts`, `account_operations` (idempotency ledger) |
| `transaction_db` | `transactions` |

The `db-init` container creates any missing database on every start (safe on existing volumes); Hibernate creates
the tables (`ddl-auto: update`).

Connect with pgAdmin / DBeaver: `localhost:5433`, user `postgres`, password from `.env`. Or:

```powershell
docker exec -it postgres psql -U postgres -d account_db
docker exec postgres pg_dumpall -U postgres > backup.sql      # backup
```

**Redis keys:** `verification:otp:{transactionId}` (5 min), `fraud:velocity:{account}` (60 s),
`fraud:avg_amount:{account}`, `fraud:processed:{transactionId}` (24 h), plus the gateway's rate-limit buckets.

---

## Risk Lab (WebAssembly)

<http://localhost:3000> — a **what-if simulator**, not a monitor. You describe one imaginary transfer (amount,
balance, transfers this minute, usual amount) and it shows the decision, which rules fired, what the customer would be
told and the new usual amount. It runs the bank's real Rust rules (`risk-core`) compiled to WebAssembly, so its
answers match the live engine — but it has no connection to accounts, Redis or Kafka and never moves money.

Features: example scenarios (shareable links such as `/#bot-burst`), sliders, rule meters, a speed-dot view of
velocity, editable rule thresholds, a table of every example, and an in-browser speed test.

---

## API reference

All client calls go through the gateway at `http://<host>:8080/api/v1` with `Authorization: Bearer <token>`
(except sign-up and log-in).

| Method | Path | |
|---|---|---|
| `POST` | `/auth/register` | `{ fullName, email, phone, password, initialDeposit }` → token + user (opens the account) |
| `POST` | `/auth/login` | `{ email, password }` → token + user |
| `GET` | `/auth/me` | current user |
| `GET` | `/accounts/{accountNumber}` | own account (balance, IBAN, status) |
| `GET` | `/accounts/{accountNumber}/balance` | own balance |
| `GET` | `/accounts/iban/{iban}` | recipient check → `{ iban, displayName, canReceive }` |
| `POST` | `/transactions/transfer` | `{ senderAccountNumber, receiverIban, amount, description }` |
| `GET` | `/transactions/{id}` | own transfer (incl. `verificationSecondsRemaining`) |
| `GET` | `/transactions/account/{accountNumber}` | own transfer history |
| `POST` | `/transactions/{id}/verify?otp=123456` | confirm with the code |
| `POST` | `/transactions/{id}/report-fraud` | "I didn't make this transfer" |

Risk engine (internal, `localhost:8086`): `POST /api/v1/risk/evaluate`, `POST /api/v1/risk/evaluate/batch`
(≤ 1000, parallel threads), `GET /api/v1/risk/config`, `GET /metrics`, `GET /health`.

---

## Configuration

`.env` (copy from `.env.example`; never commit it):

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL password |
| `JWT_SECRET` | signs (user-service) and validates (gateway) tokens; ≥ 32 characters; changing it logs everyone out |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_SMTP_AUTH` | real email via Resend (optional) |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | SMS via Twilio (optional) |

`mobile/.env` (optional): `EXPO_PUBLIC_API_URL=http://<PC-LAN-IP>:8080` if the app can't find the gateway on its own.

Other settings: fraud thresholds (`fraud.*`, `RISK_*`), IBAN bank code (`bank.iban.*` in account-service), OTP expiry
and recovery timings (`transaction.*` in transaction-service).

---

## Repository layout

```
api-gateway/             Spring Cloud Gateway — JWT, routing, rate limits, CORS
user-service/            sign-up, log-in, JWT issuing
account-service/         accounts, IBANs, ledger, row-locked balance changes
transaction-service/     SAGA orchestrator, OTP verification, recovery scheduler
fraud-detection-service/ fraud facts + Rust engine client + Java fallback rules
notification-service/    email / SMS notifications
risk-engine/             Rust workspace
  risk-core/               fraud rules (shared by native and WASM)
  risk-engine/             Axum + Tokio HTTP service
  risk-wasm/               wasm-bindgen bindings
risk-dashboard/          Risk Lab — React + TypeScript + WebAssembly
mobile/                  Retech Bank app — Expo SDK 58, Expo Router
docker/postgres/         database bootstrap script
scripts/smoke-test.ps1   end-to-end test against the running stack
docker-compose.yml       the whole system
pom.xml                  Maven aggregator (build/test every Java service at once)
CLAUDE.md                project requirements
```

---

## Development

**Run a Java service from the IDE.** Defaults in each `application.yml` point at `localhost` (PostgreSQL **5433**,
Kafka 9092, Redis 6379, SMTP 1025, other services on their ports). Start the infrastructure:

```powershell
docker compose up -d postgres db-init redis zookeeper kafka risk-engine mailpit
```

**Mobile.** `cd mobile; npm install; npm start` — the dev server runs on **8088** (8081 belongs to account-service).
Press `a` for the Android emulator or `w` for the browser, or scan the QR code with Expo Go. The app finds the
gateway on the PC that serves the bundle. Expo Go must support **SDK 58**.

**Risk Lab.** `cd risk-dashboard; npm install; npm run dev` (needs `rustup target add wasm32-unknown-unknown`).

**Risk engine.** `cd risk-engine; cargo run -p risk-engine` (port 8086).

---

## Testing

| Suite | Command | Count |
|---|---|---|
| Rust | `cd risk-engine; cargo test --workspace` | 18 |
| Java (unit + PostgreSQL integration via Testcontainers) | see below | 101 (+2 opt-in benchmarks) |
| Mobile | `cd mobile; npx tsc --noEmit; npx expo lint` | typecheck + lint |
| End to end | `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1` | 41 checks |

Java tests without a local Maven install:

```powershell
docker run --rm -v "${PWD}:/workspace" -v banking-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9-eclipse-temurin-17 mvn -B test
```

The smoke test signs users up, transfers by IBAN, reads codes from the emails in Mailpit, and checks every path
(allow, correct / wrong code, "not me", insufficient balance) plus the security rules (no token, forged token,
other users' data, internal endpoints).

---

## Benchmarks

| What | How |
|---|---|
| Native Rust, in-process | `cd risk-engine; cargo run --release -p risk-core --example bench` |
| Java rules + Rust over HTTP | `mvn -pl fraud-detection-service test -Dtest=RiskEngineBenchmarkTest -Dbenchmark=true` |
| WebAssembly in the browser | Risk Lab → Speed test |
| Container memory | `docker stats risk-engine fraud-detection-service` |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Project is incompatible with this version of Expo Go" | Update Expo Go (Play Store). On an emulator: `adb uninstall host.exp.exponent`, then press `a` in Expo. |
| Phone can't reach the bank | Same Wi-Fi as the PC; allow port 8080 in Windows Firewall (Private network) — see `mobile/README.md`. |
| `ports are not available … 5432` | Use host port 5433 (already configured); a native PostgreSQL owns 5432. |
| Expo opens account-service instead of the app | Start Expo with `npm start` (port 8088), not on 8081. |
| Want a clean slate | `docker compose down -v` (deletes all accounts and transfers). |
| Smoke test: "connection was closed" in Windows PowerShell | Already handled in the script (keep-alive disabled). |

---

## Limitations

- Demo money only — no payment provider; the opening deposit is the only way money enters the system.
- One savings account per user; no password reset; tokens expire after 60 minutes with no refresh token.
- Transfers only between Retech Bank accounts; the bank code `00999` is fictional.
- Mailpit is a development inbox shared by all users.
- Existing amounts were relabelled from ₹ to ₺, not converted.
