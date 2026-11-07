package com.fincore.payments.merchantwebhook;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Standalone loopback-only receiver used to demonstrate signature verification and durable deduplication. */
public final class MerchantWebhookSinkMain {
    private MerchantWebhookSinkMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length!=0) throw new IllegalArgumentException("The merchant webhook sink accepts configuration through environment variables only.");
        var env=System.getenv();
        int port=Integer.parseInt(env.getOrDefault("FINCORE_MERCHANT_WEBHOOK_SINK_PORT","18082"));
        String directory=env.getOrDefault("FINCORE_MERCHANT_WEBHOOK_SINK_DIRECTORY",".merchant-webhook-sink");
        String secret=env.get("FINCORE_MERCHANT_WEBHOOK_SINK_SECRET");
        String keyId=env.get("FINCORE_MERCHANT_WEBHOOK_SINK_SUBSCRIPTION_ID");
        UUID subscription=UUID.fromString(keyId==null ? "00000000-0000-0000-0000-000000000000" : keyId);
        try (var sink=new MerchantWebhookSink(port,Path.of(directory),secret,subscription.toString())) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { sink.close(); } catch (Exception ignored) { } },"merchant-webhook-sink-shutdown"));
            System.out.println("MERCHANT_WEBHOOK_SINK_READY port="+sink.port()+" receipts="+sink.receiptCount());
            new CountDownLatch(1).await();
        }
    }
}
