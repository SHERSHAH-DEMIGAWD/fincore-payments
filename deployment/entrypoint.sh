#!/bin/sh
set -eu

# Fixed allowlist. Secret values are never evaluated as shell source or printed.
for name in DB_PASSWORD FINCORE_DEMO_A_PASSWORD FINCORE_DEMO_B_PASSWORD FINCORE_DEMO_OBSERVER_PASSWORD FINCORE_DEMO_OPERATOR_PASSWORD FINCORE_OBSERVABILITY_PASSWORD_HASH FINCORE_OPERATIONS_PASSWORD_HASH FINCORE_PROVIDER_API_KEY FINCORE_SANDBOX_API_KEY FINCORE_PROVIDER_WEBHOOK_SIGNING_KEY FINCORE_SANDBOX_WEBHOOK_KEY FINCORE_SETTLEMENT_SIGNING_KEY FINCORE_SANDBOX_SETTLEMENT_SIGNING_KEY FINCORE_MERCHANT_WEBHOOK_MASTER_KEY FINCORE_PROVIDER_INSTANCE_ID FINCORE_PROVIDER_WEBHOOK_INSTANCE_ID FINCORE_SETTLEMENT_INSTANCE_ID FINCORE_SANDBOX_INSTANCE_ID FINCORE_DEMO_SUBSCRIPTION_REQUEST_ID; do
    file=$(printenv "${name}_FILE" || true)
    if [ -n "$file" ]; then
        plain=$(printenv "$name" || true)
        if [ -n "$plain" ] || [ ! -f "$file" ]; then
            printf '%s\n' 'LOCAL_SANDBOX_SECRET_CONFIGURATION_REFUSED' >&2
            exit 2
        fi
        value=$(cat "$file")
        if [ -z "$value" ]; then exit 2; fi
        export "$name=$value"
        unset "${name}_FILE"
    fi
done

if [ "${1:-}" = provider-sandbox ] && [ "$#" -eq 1 ]; then
    set -- provider-sandbox "--port=${FINCORE_SANDBOX_PORT:-18081}" "--data-dir=${FINCORE_SANDBOX_DATA_DIRECTORY:-/data/provider}" "--instance-id=${FINCORE_SANDBOX_INSTANCE_ID:?}"
fi
exec "${JAVA_BIN:-java}" -XX:MaxRAMPercentage=65 -jar "${FINCORE_APP_JAR:-/app/app.jar}" "$@"
