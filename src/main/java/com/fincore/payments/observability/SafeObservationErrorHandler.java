package com.fincore.payments.observability;

import io.micrometer.observation.*;
import org.springframework.core.Ordered;

/** Runs before tracing handlers: exception messages/stack traces are not telemetry attributes. */
public class SafeObservationErrorHandler implements ObservationHandler<Observation.Context>,Ordered {
    public static final class Failure extends RuntimeException {
        public Failure(){super("operation failed",null,false,false);}
    }
    @Override public boolean supportsContext(Observation.Context context){return true;}
    @Override public void onError(Observation.Context context){if(context.getError()!=null)context.setError(new Failure());}
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
}
