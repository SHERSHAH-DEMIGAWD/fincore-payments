package com.fincore.payments.observability;

import com.fincore.payments.dto.*;
import com.fincore.payments.exception.PaymentRiskException;
import com.fincore.payments.risk.RiskVelocity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FlowTelemetryTest {
    @Test void classificationFailureCannotReplaceTheBusinessReturnValue()throws Throwable{
        var telemetry=telemetry();var returned=new ProviderOperation.Result(null,false);
        assertThat(telemetry.observe(Flow.PROVIDER_COMMAND,()->returned)).isSameAs(returned);
        assertThat(meters.get("fincore.flow.calls").tag("outcome","unclassified").counter().count()).isEqualTo(1);
    }
    final SimpleMeterRegistry meters=new SimpleMeterRegistry();final ObservationRegistry observations=ObservationRegistry.create();
    FlowTelemetry telemetry(){observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));return new FlowTelemetry(meters,observations);}
    @Test void oneCallOneTimerAndBoundedOutcome()throws Throwable{
        var telemetry=telemetry();assertThat(telemetry.observe(Flow.PAYMENT_CREATE,()->"ok")).isEqualTo("ok");
        assertThat(meters.get("fincore.flow.calls").tags("flow","payment_create","outcome","success").counter().count()).isEqualTo(1);
        assertThat(meters.get("fincore.flow").tags("flow","payment_create","outcome","success").timer().count()).isEqualTo(1);
        assertThat(observations.getCurrentObservation()).isNull();
    }
    @Test void originalExceptionIsPreservedButObservationGetsSanitizedFailure(){
        var recorded=new java.util.ArrayList<Throwable>();observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}public void onError(Observation.Context c){recorded.add(c.getError());}});
        var telemetry=telemetry();var original=new IllegalStateException("private-key-and-sql");
        assertThatThrownBy(()->telemetry.observe(Flow.PAYMENT_PERSIST,()->{throw original;})).isSameAs(original);
        assertThat(recorded).hasSize(1);assertThat(recorded.getFirst()).isInstanceOf(SafeObservationErrorHandler.Failure.class).hasMessage("operation failed").hasNoCause();
        assertThat(recorded.getFirst().getStackTrace()).isEmpty();assertThat(meters.get("fincore.flow.calls").tag("outcome","error").counter().count()).isEqualTo(1);
    }
    @Test void brokenObservationHandlerCannotRepeatOrUndoBusinessWork()throws Throwable{
        var telemetry=telemetry();var calls=new AtomicInteger();observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>(){
            public boolean supportsContext(Observation.Context c){return true;}public void onStop(Observation.Context c){throw new IllegalStateException("broken telemetry");}});
        assertThat(telemetry.observe(Flow.PAYMENT_CREATE,()->calls.incrementAndGet())).isEqualTo(1);
        assertThat(calls).hasValue(1);assertThat(meters.get("fincore.telemetry.failures").gauge().value()).isPositive();assertThat(observations.getCurrentObservation()).isNull();
    }
    @Test void outcomesDistinguishRetryTemporaryRefusalAndEmptyWork(){
        assertThat(FlowTelemetry.result(Flow.RISK_CHECK,null)).isEqualTo(FlowTelemetry.Outcome.REPLAY);
        assertThat(FlowTelemetry.result(Flow.REDIS_RESERVATION,new RiskVelocity.Reservation(false,2,1000))).isEqualTo(FlowTelemetry.Outcome.THROTTLED);
        assertThat(FlowTelemetry.result(Flow.OUTBOX_DELIVERY,false)).isEqualTo(FlowTelemetry.Outcome.IDLE);
        assertThat(FlowTelemetry.result(Flow.CONSUMER_APPLY,false)).isEqualTo(FlowTelemetry.Outcome.REPLAY);
        assertThat(FlowTelemetry.failure(new PaymentRiskException(503,"x","private",null,null))).isEqualTo(FlowTelemetry.Outcome.UNAVAILABLE);
    }
}
