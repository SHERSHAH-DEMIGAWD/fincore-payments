# Merchant webhook sandbox runbook

This runbook demonstrates Milestone 19 locally. The receiver binds only to loopback. The app and receiver default to disabled. Do not use production credentials or a public destination.

## 1. Start PostgreSQL and the API

Start the project's PostgreSQL database as described in [README setup](../README.md#setup). In PowerShell, create a temporary AES-256 key for this demonstration and configure the exact receiver URL allowlist before starting the app:

```powershell
$env:FINCORE_MERCHANT_WEBHOOKS_ENABLED = 'true'
$env:FINCORE_MERCHANT_WEBHOOK_DISPATCH_ENABLED = 'true'
$env:FINCORE_MERCHANT_WEBHOOK_MASTER_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:FINCORE_MERCHANT_WEBHOOK_MASTER_KEY_ID = 'local-demo-v1'
$env:FINCORE_MERCHANT_WEBHOOK_ALLOWED_DESTINATIONS = 'http://127.0.0.1:18082/webhooks'
.mvnw.cmd spring-boot:run
```

Keep the key only in the terminal environment; never commit it. Dispatch runs in the background only because it is explicitly enabled here; both webhook switches default off. Ensure your merchant account has been provisioned using [merchant access setup](MERCHANT_ACCESS_RUNBOOK.md).

## 2. Create a subscription

Create a subscription as the authenticated merchant with a fresh UUID `requestId` and the exact allowlisted URL. Use the existing merchant Basic/session-CSRF procedure. `POST /api/v1/merchant/webhooks/subscriptions` returns a subscription UUID and its signing secret. Save both for the local sink; the response is the only place that exposes the secret. An exact retry of the same request returns the same original secret, while changed intent conflicts.

Example body:

```json
{"requestId":"<new-request-uuid>","destinationUrl":"http://127.0.0.1:18082/webhooks"}
```

## 3. Start the signed, durable local receiver

In a second PowerShell terminal, supply the returned secret and subscription UUID through environment variables. Use a fresh private sink directory per demonstration:

```powershell
$env:FINCORE_MERCHANT_WEBHOOK_SINK_SECRET = '<returned-signing-secret>'
$env:FINCORE_MERCHANT_WEBHOOK_SINK_SUBSCRIPTION_ID = '<returned-subscription-uuid>'
$env:FINCORE_MERCHANT_WEBHOOK_SINK_DIRECTORY = '.merchant-webhook-sink-demo'
$env:FINCORE_MERCHANT_WEBHOOK_SINK_PORT = '18082'
.mvnw.cmd spring-boot:run '-Dspring-boot.run.main-class=com.fincore.payments.merchantwebhook.MerchantWebhookSinkMain'
```

Wait for `MERCHANT_WEBHOOK_SINK_READY`. The sink verifies the signature/freshness, stores a bounded duplicate-protected receipt journal and responds 204. Stop it with Ctrl+C. Its journal is local demo evidence, not a backup or power-loss guarantee.

## 4. Trigger and inspect a payment event

Create a new payment and authorize/capture it using the merchant API and its normal CSRF flow. Subscription enrollment starts when the payment event is inserted; old events are not backfilled. The scheduler claims and delivers due events. For a controlled non-scheduled integration check, the test suite exercises `MerchantWebhookTransactions.claimNext` → `MerchantWebhookHttpClient.send` → `finish` directly.

Merchant-owned APIs:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/merchant/webhooks/subscriptions` | Create an allowlisted destination and signing key. |
| GET | `/api/v1/merchant/webhooks/subscriptions?limit=50` | List owned subscriptions. |
| POST | `/api/v1/merchant/webhooks/subscriptions/{subscriptionId}/state` | Pause/resume with expected revision and audit reason. |
| GET | `/api/v1/merchant/webhooks/deliveries?paymentId={id}` | Inspect safe delivery status for an owned payment. |
| GET | `/api/v1/merchant/webhooks/deliveries/{deliveryId}/attempts` | Inspect immutable attempt history. |
| POST | `/api/v1/merchant/webhooks/deliveries/{deliveryId}/retry` | Retry a DEAD delivery with expected generation and reason. |

Delivery status `DELIVERED` means the HTTP client received a complete 2xx response. It does not assert what the receiver did after acknowledging.

## 5. Failure and recovery

- A timeout or connection break is `NETWORK_UNCONFIRMED`; the receiver might already have stored the body. Retries keep the same delivery ID/body and re-sign with a current timestamp.
- Non-2xx replies are `HTTP_REJECTED`; capped exponential backoff applies.
- After the configured retry cycle is exhausted, the delivery becomes `DEAD`; later payment versions for the same subscription/payment wait behind it.
- Fix the receiver/configuration, then retry with a new request UUID, the current `retryGeneration`, and an explanatory reason. Older attempt rows remain immutable.
- If a retry response is lost, repeat the exact same request UUID/body. Changed reuse conflicts.
- `blockedReason` distinguishes disabled feature, disabled merchant, paused subscription, missing current key ID, disallowed destination, earlier event, lease, backoff, and dead state.

## 6. Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `FINCORE_MERCHANT_WEBHOOKS_ENABLED` | `false` | Permit subscription creation and delivery processing. |
| `FINCORE_MERCHANT_WEBHOOK_DISPATCH_ENABLED` | `false` | Start the periodic outbound worker; requires feature enabled. |
| `FINCORE_MERCHANT_WEBHOOK_MASTER_KEY` | empty | Canonical Base64 32-byte AES-GCM key; required when enabled. |
| `FINCORE_MERCHANT_WEBHOOK_MASTER_KEY_ID` | `local-master-v1` | Identifier bound to ciphertext; this milestone has one active key. |
| `FINCORE_MERCHANT_WEBHOOK_ALLOWED_DESTINATIONS` | empty | Exact canonical literal-loopback HTTP(S) URLs, comma-separated. |
| `FINCORE_MERCHANT_WEBHOOK_MAX_SUBSCRIPTIONS` | `5` | Merchant subscription cap (1–20). |
| `FINCORE_MERCHANT_WEBHOOK_BATCH_SIZE` | `20` | Maximum claims per scheduler pass (1–100). |
| `FINCORE_MERCHANT_WEBHOOK_TIMEOUT_MILLIS` | `2000` | Complete HTTP response timeout (100–10,000 ms). |
| `FINCORE_MERCHANT_WEBHOOK_LEASE_SECONDS` | `30` | Claim lease; must exceed timeout plus two seconds. |
| `FINCORE_MERCHANT_WEBHOOK_MAX_ATTEMPTS` | `5` | Attempts per cycle (1–20). |
| `FINCORE_MERCHANT_WEBHOOK_INITIAL_BACKOFF_MILLIS` | `1000` | First retry delay. |
| `FINCORE_MERCHANT_WEBHOOK_MAX_BACKOFF_MILLIS` | `60000` | Exponential delay cap. |
| `FINCORE_MERCHANT_WEBHOOK_FIXED_DELAY_MS` | `1000` | Scheduler delay between batches. |

## 7. Current limits

One key ID/master key is active at a time; rotate only after a future safe re-encryption workflow. Destinations are loopback-only. There is no deletion/retention executor, public-network egress policy, multi-region dispatch, downstream exactly-once guarantee or production service-level objective. See the [learning guide](MILESTONE_19_LEARNING_GUIDE.md) for the alternatives and system-level tradeoffs.
