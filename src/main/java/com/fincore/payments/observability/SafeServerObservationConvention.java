package com.fincore.payments.observability;

import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.*;
import java.util.Set;

/** Uses framework route templates, never raw paths, query parameters, bodies or credentials. */
public class SafeServerObservationConvention implements ServerRequestObservationConvention {
    private static final Set<String> METHODS=Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE");
    @Override public String getName(){return "http.server.requests";}
    private static String method(ServerRequestObservationContext c){var m=c.getCarrier().getMethod();return METHODS.contains(m)?m:"OTHER";}
    private static String route(ServerRequestObservationContext c){var path=c.getPathPattern();return path==null?"unmatched":path;}
    @Override public String getContextualName(ServerRequestObservationContext c){return "HTTP "+method(c)+" "+route(c);}
    @Override public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext c){return KeyValues.empty();}
    @Override public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext c){
        int status=c.getResponse()==null?0:c.getResponse().getStatus();
        String outcome=switch(status/100){case 1->"INFORMATIONAL";case 2->"SUCCESS";case 3->"REDIRECTION";case 4->"CLIENT_ERROR";case 5->"SERVER_ERROR";default->"UNKNOWN";};
        return KeyValues.of("method",method(c),"uri",route(c),"status",status>=100&&status<=599?Integer.toString(status):"UNKNOWN",
            "outcome",outcome,"exception",c.getError()==null?"none":"error");
    }
}
