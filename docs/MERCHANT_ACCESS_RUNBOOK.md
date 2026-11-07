# Merchant access runbook — Milestone 16

**Milestone 17 continuation:** for a new provider-managed payment, use the [sandbox provider runbook](PROVIDER_INTEGRATION_RUNBOOK.md) after merchant setup. Provider commands reuse these credentials/CSRF rules. Once bound, manual authorize/capture/fail/refund return 409; the manual examples below apply to unbound teaching payments.

Use the [learning guide](MILESTONE_16_LEARNING_GUIDE.md) for concepts/tradeoffs, [summary](MILESTONE_16_SUMMARY.md) for tests and [roadmap](ROADMAP.md) for future scope. All payment, event, projection and ledger HTTP endpoints now require merchant authentication. The operator API retains its [separate credentials and CSRF flow](PROJECTION_ROUTING_RUNBOOK.md#1-configure-the-servlet-operator-boundary).

## 1. Upgrade and provision an account

Stop older HTTP application instances before applying V16; this requires a coordinated upgrade, with no mixed-version deployment. An older public-API binary does not enforce the new merchant boundary or composite-key contract. Start the updated application with the usual Java 21/PostgreSQL settings so Flyway applies V16. No merchant account or password is enabled automatically. Old payments/claims remain in the sealed `legacy-internal` scope and are not assigned to newly provisioned accounts.

Generate a raw BCrypt verifier with a trusted implementation such as Spring Security `BCryptPasswordEncoder.encode`. For ordinary use choose an appropriately measured cost of at least 10; the database permits costs 4–16, with 4 used only in disposable tests. Do not store plaintext, a `{bcrypt}` prefix or a verifier in source control. API authentication uses the original password, not this verifier.

Using a privileged PostgreSQL administration session connected to the intended application database, run this **psql** workflow:

```sql
\prompt 'Merchant username: ' merchant_username
\prompt 'Raw BCrypt verifier: ' merchant_hash
INSERT INTO merchants(id,username,password_hash,enabled)
VALUES (gen_random_uuid(), :'merchant_username', :'merchant_hash', true)
RETURNING id,username,enabled;
\unset merchant_hash
\unset merchant_username
```

This uses psql's quoted variables, not SQL string concatenation. Usernames are case-sensitive, 1–100 ASCII characters: letters/digits/dot/underscore/hyphen, starting with a letter/digit. Use a distinct identity for each merchant. UUIDs and usernames are immutable after creation. There is no HTTP signup, account-admin, password reset or ownership adoption endpoint.

Provisioning is privileged database administration. The runtime isolation protects merchant HTTP callers; it does not restrict a database owner or trusted operations tools from cross-merchant access.

## 2. Authenticate and obtain a CSRF token

Use HTTPS outside local loopback. Basic authentication sends credentials on every request; it does not encrypt the transport.

PowerShell setup from the project directory:

```powershell
$merchantBase = 'http://localhost:8080'
$merchantCredential = Get-Credential -Message 'Provisioned merchant username and original password'
$merchantBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $merchantCredential.UserName + ':' + $merchantCredential.GetNetworkCredential().Password))
$merchantHeaders = @{ Authorization = 'Basic ' + $merchantBasic }
$merchantSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$merchantIdentity = Invoke-RestMethod -Uri "$merchantBase/api/v1/merchant/me" `
    -Headers $merchantHeaders -WebSession $merchantSession
$merchantCsrf = Invoke-RestMethod -Uri "$merchantBase/api/v1/merchant/csrf" `
    -Headers $merchantHeaders -WebSession $merchantSession
$merchantHeaders[$merchantCsrf.headerName] = $merchantCsrf.token
$merchantIdentity
```

`/me` returns only merchant UUID and username. Retain the headers and CSRF session locally as sensitive state. Every request still requires Basic credentials; the cookie alone is not a login. POST requires both token and matching cookie. Refresh the token/session after expiration or application restart. No token in a request body or merchant-ID header chooses ownership.

## 3. Create, read and transition an owned payment

```powershell
$merchantKey = [guid]::NewGuid().ToString()
$merchantCreateHeaders = $merchantHeaders.Clone()
$merchantCreateHeaders['Idempotency-Key'] = $merchantKey
$merchantBody = '{"merchantReference":"ORDER-123","amount":1499.99,"currency":"INR"}'

$merchantPayment = Invoke-RestMethod -Method Post -Uri "$merchantBase/api/v1/payments" `
    -Headers $merchantCreateHeaders -WebSession $merchantSession `
    -ContentType 'application/json' -Body $merchantBody
$merchantPaymentId = $merchantPayment.id

Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$merchantPaymentId" `
    -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Method Post -Uri "$merchantBase/api/v1/payments/$merchantPaymentId/authorize" `
    -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Method Post -Uri "$merchantBase/api/v1/payments/$merchantPaymentId/capture" `
    -Headers $merchantHeaders -WebSession $merchantSession
```

First creation returns 201. Repeating the same key/body as the same merchant returns 200 and the original creation snapshot. Changed intent under that merchant/key returns 409. Another merchant can use the same key independently. Payment transitions retain the existing lifecycle; full refunds use `/refund` after capture, and `/fail` applies only in allowed pre-capture states.

## 4. Read owned financial/event views

Using the same Basic headers/session:

```powershell
Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$merchantPaymentId/ledger" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$merchantPaymentId/events" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/payments/$merchantPaymentId/projection" -Headers $merchantHeaders -WebSession $merchantSession
Invoke-RestMethod -Uri "$merchantBase/api/v1/ledger/accounts/INR/balances" -Headers $merchantHeaders -WebSession $merchantSession
```

Foreign and missing payment UUIDs both return `404 PAYMENT_NOT_FOUND`, including events/ledger/projection. An owned payment whose consumer projection has not arrived returns `404 PAYMENT_PROJECTION_NOT_FOUND`. A consumer-only snapshot grants no merchant access without a matching owned source payment.

Balances aggregate only journals for this merchant's payments. No posted journals means `[]`, even if other merchants have accounts. Account definitions remain pooled per currency; these scoped totals are not settlement, bank balances or a wallet.

## 5. Rotate or disable credentials

With the correct merchant UUID and a new trusted BCrypt verifier, use a privileged **psql** session:

```sql
\prompt 'Existing merchant UUID: ' merchant_id
\prompt 'Replacement raw BCrypt verifier: ' replacement_hash
UPDATE merchants SET password_hash=:'replacement_hash' WHERE id=:'merchant_id'::uuid;
\unset replacement_hash
-- To disable the account:
UPDATE merchants SET enabled=false WHERE id=:'merchant_id'::uuid;
\unset merchant_id
```

Credentials are checked on each new request, so old cookies do not preserve a disabled account or old password. An already authenticated in-flight operation can complete. This milestone does not implement credential-change audits, password recovery, forced in-flight cancellation, MFA or an external identity provider. The reserved legacy identity cannot be enabled.

## 6. Failure guide and boundaries

| Result | Check |
| --- | --- |
| 401 `AUTHENTICATION_REQUIRED` | Missing, unknown, wrong or disabled merchant credentials; do not use an operator password or BCrypt verifier as the password. |
| 403 `ACCESS_DENIED` | Missing/invalid CSRF token/cookie on writes, wrong principal/role, or a denied route. Anonymous POST may fail CSRF before authentication. |
| 404 `PAYMENT_NOT_FOUND` | Missing payment or another merchant/legacy scope; IDs alone grant no authority. |
| 404 `PAYMENT_PROJECTION_NOT_FOUND` | Owned payment exists but no routed consumer snapshot is available. |
| 409 idempotency/state/concurrency error | Follow the existing exact-retry/state rules within the authenticated merchant scope. |

The servlet boundary is deliberately closed when no merchant credentials have been provisioned. Headless operations and the separate operator account remain privileged. There is no RLS/database-per-tenant isolation, rate limiting, OIDC/MFA, provider integration, signed webhooks or completed production hardening in this milestone.
