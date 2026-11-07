package com.fincore.payments.observability;

import com.fincore.payments.dto.*;
import com.fincore.payments.exception.*;
import com.fincore.payments.merchantwebhook.MerchantWebhookHttpClient;
import com.fincore.payments.risk.RiskVelocity;
import com.fincore.payments.provider.ProviderUncertainException;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic calls/outcomes, not an exactly-once count of financial effects. */
public class FlowTelemetry {
    public enum Outcome { SUCCESS,REPLAY,IDLE,PARTIAL,DECLINED,UNCERTAIN,REVIEW,REJECTED,THROTTLED,UNAVAILABLE,ERROR,ALLOW,DENY,UNCLASSIFIED;
        public String code(){return name().toLowerCase(Locale.ROOT);} }
    @FunctionalInterface public interface Work {Object run()throws Throwable;}
    private final MeterRegistry meters;private final ObservationRegistry observations;
    private final AtomicLong failures=new AtomicLong();
    public FlowTelemetry(MeterRegistry meters,ObservationRegistry observations){this.meters=meters;this.observations=observations;
        Gauge.builder("fincore.telemetry.failures",failures,AtomicLong::doubleValue).description("Instrumentation failures in this process").register(meters);}
    public Object observe(Flow flow,Work work)throws Throwable{
        Observation observation=null;Observation.Scope scope=null;Outcome outcome=Outcome.ERROR;
        try{observation=Observation.createNotStarted("fincore.flow",observations).contextualName(flow.code())
            .lowCardinalityKeyValue("flow",flow.code());observation.start();scope=observation.openScope();}
        catch(RuntimeException telemetryFailure){failures.incrementAndGet();}
        try{
            Object result=work.run();
            try{outcome=result(flow,result);}catch(RuntimeException dropped){failures.incrementAndGet();outcome=Outcome.UNCLASSIFIED;}
            return result;
        }catch(Throwable original){
            outcome=failure(original);
            if(observation!=null)try{observation.error(new SafeObservationErrorHandler.Failure());}catch(RuntimeException dropped){failures.incrementAndGet();}
            throw original;
        }finally{
            if(observation!=null)try{observation.lowCardinalityKeyValue("outcome",outcome.code());}catch(RuntimeException dropped){failures.incrementAndGet();}
            try{LoggerFactory.getLogger(FlowTelemetry.class).debug("flow={} outcome={}",flow.code(),outcome.code());}catch(RuntimeException dropped){failures.incrementAndGet();}
            if(scope!=null)try{scope.close();}catch(RuntimeException dropped){failures.incrementAndGet();}
            if(observation!=null)try{observation.stop();}catch(RuntimeException dropped){failures.incrementAndGet();}
            try{meters.counter("fincore.flow.calls","flow",flow.code(),"outcome",outcome.code()).increment();}catch(RuntimeException dropped){failures.incrementAndGet();}
        }
    }
    static Outcome result(Flow flow,Object result){
        if(result instanceof CreatePaymentResult r)return r.created()?Outcome.SUCCESS:Outcome.REPLAY;
        if(result instanceof PaymentAccounting.Result r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof ProviderOperation.Result r)return switch(r.operation().state()){
            case APPLIED->Outcome.SUCCESS;case DECLINED->Outcome.DECLINED;case REQUIRES_REVIEW->Outcome.REVIEW;default->Outcome.UNCERTAIN;};
        if(result instanceof ProviderWebhook.Acknowledgement r)return switch(r.disposition()){
            case CONFIRMED->Outcome.SUCCESS;case ALREADY_RECORDED->Outcome.REPLAY;case REQUIRES_REVIEW->Outcome.REVIEW;default->Outcome.REJECTED;};
        if(flow==Flow.RISK_CHECK){if(result==null)return Outcome.REPLAY;
            if(result instanceof PaymentRisk.Decision d)return switch(d.outcome()){
                case ALLOW->Outcome.ALLOW;case DENY->Outcome.DENY;case THROTTLED->Outcome.THROTTLED;case UNAVAILABLE->Outcome.UNAVAILABLE;};}
        if(result instanceof RiskVelocity.Reservation r)return r.allowed()?Outcome.ALLOW:Outcome.THROTTLED;
        if(result instanceof MerchantWebhookHttpClient.Outcome r)return r.acknowledged()?Outcome.SUCCESS:Outcome.UNAVAILABLE;
        if(result instanceof MerchantWebhook.Dispatch r)return r.claimed()==0?Outcome.IDLE:r.failed()>0||r.abandoned()>0?Outcome.PARTIAL:Outcome.SUCCESS;
        if(result instanceof Settlement.ImportResult r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof Settlement.ReportResult r)return r.replayed()?Outcome.REPLAY:r.report().comparison().matched()?Outcome.SUCCESS:Outcome.REVIEW;
        if(result instanceof Settlement.ResolutionResult r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof ProjectionRoutingResult r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof RequeueResult r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof ConsumerRecoveryResult r)return r.replayed()?Outcome.REPLAY:Outcome.SUCCESS;
        if(result instanceof Boolean r)return r?Outcome.SUCCESS:flow==Flow.CONSUMER_APPLY?Outcome.REPLAY:Outcome.IDLE;
        if(result instanceof Integer r && flow==Flow.OUTBOX_BATCH)return r==0?Outcome.IDLE:Outcome.SUCCESS;
        return Outcome.SUCCESS;
    }
    static Outcome failure(Throwable error){
        if(error instanceof PaymentRiskException e)return e.status()==429?Outcome.THROTTLED:e.status()==503?Outcome.UNAVAILABLE:Outcome.REJECTED;
        if(error instanceof ProviderUncertainException)return Outcome.UNCERTAIN;
        if(error instanceof OutboxPublishException)return Outcome.ERROR;
        if(error instanceof IllegalArgumentException || error.getClass().getPackageName().equals("com.fincore.payments.exception"))return Outcome.REJECTED;
        return Outcome.ERROR;
    }
}
