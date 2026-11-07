package com.fincore.payments.config;

import com.fincore.payments.observability.*;
import com.fincore.payments.repository.OperationalSnapshotRepository;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfiguration {
    @Bean EndpointFilter<ExposableWebEndpoint> optInOperationalEndpoints(ObservabilityProperties properties){
        return endpoint->properties.enabled() && Set.of("health","prometheus").contains(endpoint.getEndpointId().toString());
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="fincore.observability.enabled",havingValue="true")
    static class Enabled {
        @Bean FlowTelemetry flowTelemetry(MeterRegistry meters,ObservationRegistry observations){return new FlowTelemetry(meters,observations);}
        @Bean FlowObservationAspect flowObservationAspect(FlowTelemetry telemetry){return new FlowObservationAspect(telemetry);}
        @Bean OperationalSnapshot operationalSnapshot(MeterRegistry meters,ObservabilityProperties p){return new OperationalSnapshot(meters,p.snapshotIntervalMillis());}
        @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="fincore.observability.collector-enabled",havingValue="true",matchIfMissing=true)
        OperationalSnapshotCollector operationalSnapshotCollector(OperationalSnapshotRepository repository,OperationalSnapshot snapshot,MeterRegistry meters,ObservabilityProperties p){
            return new OperationalSnapshotCollector(repository,snapshot,meters,p.snapshotIntervalMillis());}
        @Bean @ConditionalOnProperty(name="fincore.observability.trace-export-enabled",havingValue="true")
        SpanExporter localTraceExporter(ObservabilityProperties p){return OtlpHttpSpanExporter.builder().setEndpoint(p.traceEndpoint().toString())
            .setConnectTimeout(java.time.Duration.ofMillis(500)).setTimeout(java.time.Duration.ofSeconds(2)).build();}
        @Bean ObservationHandler<?> safeObservationErrorHandler(){return new SafeObservationErrorHandler();}
        @Bean ObservationPredicate omitSecurityPrincipalObservations(){return (name,context)->!name.startsWith("spring.security");}
        @Bean ServerRequestObservationConvention safeServerObservationConvention(){return new SafeServerObservationConvention();}
        @Bean MeterFilter boundedHttpRoutes(){return MeterFilter.maximumAllowableTags("http.server.requests","uri",128,MeterFilter.deny());}
        @Bean ContextPropagators traceOnlyPropagation(){
            var delegate=W3CTraceContextPropagator.getInstance();
            return ContextPropagators.create(new TextMapPropagator(){
                public Collection<String> fields(){return List.of("traceparent");}
                public <C>void inject(Context context,C carrier,TextMapSetter<C> setter){delegate.inject(context,carrier,(c,k,v)->{if(k.equals("traceparent"))setter.set(c,k,v);});}
                public <C>Context extract(Context context,C carrier,TextMapGetter<C> getter){return delegate.extract(context,carrier,new TextMapGetter<C>(){
                    public Iterable<String> keys(C c){return List.of("traceparent");}
                    public String get(C c,String key){return key.equals("traceparent")?getter.get(c,key):null;}
                });}
            });
        }
        @Bean @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
        FilterRegistrationBean<TraceCorrelationFilter> traceCorrelationFilter(Tracer tracer){
            var filter=new FilterRegistrationBean<>(new TraceCorrelationFilter(tracer));
            filter.setOrder(Ordered.HIGHEST_PRECEDENCE+5);return filter;
        }
    }
}
