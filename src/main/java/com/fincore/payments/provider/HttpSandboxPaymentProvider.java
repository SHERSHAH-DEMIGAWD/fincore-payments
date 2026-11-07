package com.fincore.payments.provider;

import com.fincore.payments.config.ProviderProperties;
import com.fincore.payments.dto.ProviderOperation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.http.*;
import java.time.Duration;

@Component
public class HttpSandboxPaymentProvider implements PaymentProvider,AutoCloseable {
    private final ProviderProperties properties;
    private final HttpClient client;
    public HttpSandboxPaymentProvider(ProviderProperties properties) {
        this.properties=properties;
        client=BoundedProviderHttp.client(properties.timeoutMillis());
    }
    @jakarta.annotation.PreDestroy public void close() { client.shutdownNow(); }
    @Override public Response execute(Request request) throws ProviderUncertainException {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider network calls must not join a database transaction.");
        try {
            ProviderWire.validate(request);
            var http=HttpRequest.newBuilder(properties.baseUrl().resolve("/v1/operations"))
                    .timeout(Duration.ofMillis(properties.timeoutMillis())).header("Content-Type","application/json")
                    .header("Authorization","Bearer "+properties.apiKey())
                    .header("Idempotency-Key",request.operationId().toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ProviderWire.mapper().writeValueAsBytes(request))).build();
            var response=BoundedProviderHttp.send(client,http,properties.timeoutMillis());
            if (response.statusCode()!=200) throw new IllegalArgumentException();
            var result=ProviderWire.read(response.body(),Response.class);
            ProviderWire.validate(result,request);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ProviderUncertainException();
        } catch (Exception uncertain) { throw new ProviderUncertainException(); }
    }
}
