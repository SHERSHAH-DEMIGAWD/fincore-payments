package com.fincore.payments.observability;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
class SafeServerObservationConventionTest {
    @Test void rawPathsQueriesCredentialsAndUnboundedMethodsAreNotDimensions(){
        var request=new MockHttpServletRequest("MALICIOUS-private-value","/api/v1/payments/private-id");request.setQueryString("key=private-key");request.addHeader("Authorization","private-auth");
        var context=new ServerRequestObservationContext(request,new MockHttpServletResponse());context.setPathPattern("/api/v1/payments/{paymentId}");
        context.setError(new IllegalArgumentException("private-exception"));var convention=new SafeServerObservationConvention();
        String recorded=convention.getLowCardinalityKeyValues(context).toString()+convention.getContextualName(context);
        assertThat(recorded).contains("OTHER","{paymentId}").doesNotContain("private");assertThat(convention.getHighCardinalityKeyValues(context)).isEmpty();
    }
    @Test void errorHandlerKeepsOnlyFixedFailureMetadata(){
        var context=new io.micrometer.observation.Observation.Context();context.setError(new RuntimeException("private"));new SafeObservationErrorHandler().onError(context);
        assertThat(context.getError()).hasMessage("operation failed").hasNoCause();assertThat(context.getError().getStackTrace()).isEmpty();
    }
}
