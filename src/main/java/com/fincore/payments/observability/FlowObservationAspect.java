package com.fincore.payments.observability;

import com.fincore.payments.dto.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Outside transaction advice: a deferred commit failure remains an observed failure. */
@Aspect @Order(Ordered.HIGHEST_PRECEDENCE+100)
public class FlowObservationAspect {
    private final FlowTelemetry telemetry;
    public FlowObservationAspect(FlowTelemetry telemetry){this.telemetry=telemetry;}
    @Around("execution(public * com.fincore.payments.service.MerchantPaymentService.*Payment(..)) || "
        +"execution(public * com.fincore.payments.service.PaymentService.createPayment(..)) || "
        +"execution(public * com.fincore.payments.service.PaymentAccountingService.submit(..)) || "
        +"execution(public * com.fincore.payments.service.ProviderOperationService.submit(..)) || "
        +"execution(public * com.fincore.payments.service.ProviderWebhookService.accept(..)) || "
        +"execution(public * com.fincore.payments.service.PaymentRiskTransactions.assess(..)) || "
        +"execution(public * com.fincore.payments.risk.RedisRiskVelocity.reserve(..)) || "
        +"execution(public * com.fincore.payments.provider.HttpSandboxPaymentProvider.execute(..)) || "
        +"execution(public * com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient.send(..)) || "
        +"execution(public * com.fincore.payments.service.OutboxDispatcher.dispatchBatch(..)) || "
        +"execution(public * com.fincore.payments.service.OutboxDeliveryWorker.dispatchNext(..)) || "
        +"execution(public * com.fincore.payments.service.PaymentEventConsumer.consume(..)) || "
        +"execution(public * com.fincore.payments.service.MerchantWebhookDispatcher.dispatchBatch(..)) || "
        +"execution(public * com.fincore.payments.service.EventOperationsService.requeue(..)) || "
        +"execution(public * com.fincore.payments.service.ConsumerRecoveryService.quarantine(..)) || "
        +"execution(public * com.fincore.payments.service.ConsumerRecoveryService.replay(..)) || "
        +"execution(public * com.fincore.payments.service.SettlementService.importStatement(..)) || "
        +"execution(public * com.fincore.payments.service.SettlementService.reconcile(..)) || "
        +"execution(public * com.fincore.payments.service.SettlementService.resolve(..)) || "
        +"execution(public * com.fincore.payments.service.ProjectionRoutingService.promote(..)) || "
        +"execution(public * com.fincore.payments.service.ProjectionRoutingService.rollback(..)) || "
        +"execution(public * com.fincore.payments.service.ProjectionRoutingService.replace(..)) || "
        +"execution(public * com.fincore.payments.service.ProjectionRoutingService.retire(..))")
    public Object observe(ProceedingJoinPoint join)throws Throwable{
        var signature=(MethodSignature)join.getSignature();String type=signature.getDeclaringType().getSimpleName(),method=signature.getName();
        Flow flow=switch(type){
            case "MerchantPaymentService" -> switch(method){case "createPayment"->Flow.PAYMENT_CREATE;case "authorizePayment"->Flow.PAYMENT_AUTHORIZE;
                case "capturePayment"->Flow.PAYMENT_CAPTURE;case "refundPayment"->Flow.PAYMENT_REFUND;case "failPayment"->Flow.PAYMENT_FAIL;default->null;};
            case "PaymentService"->Flow.PAYMENT_PERSIST;
            case "PaymentAccountingService" -> join.getArgs()[1] instanceof PaymentAccounting.Command c && c.action()!=null
                ?c.action()==PaymentAccounting.Action.CAPTURE?Flow.AMOUNT_CAPTURE:Flow.AMOUNT_REFUND:Flow.AMOUNT_COMMAND;
            case "ProviderOperationService" -> join.getArgs()[1] instanceof ProviderOperation.Command c && c.action()!=null?switch(c.action()){
                case AUTHORIZE->Flow.PROVIDER_AUTHORIZE;case CAPTURE->Flow.PROVIDER_CAPTURE;case REFUND->Flow.PROVIDER_REFUND;}:Flow.PROVIDER_COMMAND;
            case "ProviderWebhookService"->Flow.PROVIDER_CALLBACK;case "PaymentRiskTransactions"->Flow.RISK_CHECK;
            case "RiskVelocity","RedisRiskVelocity"->Flow.REDIS_RESERVATION;case "PaymentProvider","HttpSandboxPaymentProvider"->Flow.PROVIDER_HTTP;
            case "MerchantWebhookHttpClient"->Flow.MERCHANT_WEBHOOK_HTTP;case "MerchantWebhookDispatcher"->Flow.MERCHANT_WEBHOOK_BATCH;
            case "OutboxDispatcher"->Flow.OUTBOX_BATCH;case "OutboxDeliveryWorker"->Flow.OUTBOX_DELIVERY;case "PaymentEventConsumer"->Flow.CONSUMER_APPLY;
            case "EventOperationsService"->Flow.OUTBOX_REQUEUE;case "ConsumerRecoveryService"->method.equals("quarantine")?Flow.CONSUMER_QUARANTINE:Flow.CONSUMER_REPLAY;
            case "SettlementService"->switch(method){case "importStatement"->Flow.SETTLEMENT_IMPORT;case "reconcile"->Flow.SETTLEMENT_REPORT;default->Flow.SETTLEMENT_RESOLVE;};
            case "ProjectionRoutingService"->switch(method){case "promote"->Flow.PROJECTION_PROMOTE;case "rollback"->Flow.PROJECTION_ROLLBACK;case "replace"->Flow.PROJECTION_REPLACE;default->Flow.PROJECTION_RETIRE;};
            default->null;
        };
        return flow==null?join.proceed():telemetry.observe(flow,join::proceed);
    }
}
