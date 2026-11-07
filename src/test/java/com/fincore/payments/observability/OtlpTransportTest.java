package com.fincore.payments.observability;

import com.fincore.payments.config.ObservabilityConfiguration;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class OtlpTransportTest {
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(ObservabilityConfiguration.class)
        .withBean(MeterRegistry.class,SimpleMeterRegistry::new).withBean(ObservationRegistry.class,ObservationRegistry::create)
        .withPropertyValues("fincore.observability.enabled=true","fincore.observability.collector-enabled=false");}
    @Test void validatedExporterSendsProtobufToAnActualLocalHttpReceiver()throws Exception{
        var received=new CompletableFuture<byte[]>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/traces",exchange->{try(exchange){received.complete(exchange.getRequestBody().readNBytes(65536));
            exchange.getResponseHeaders().set("Content-Type","application/x-protobuf");exchange.sendResponseHeaders(200,-1);}});server.start();
        try{context().withPropertyValues("fincore.observability.trace-export-enabled=true","fincore.observability.trace-endpoint=http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces")
            .run(c->{assertThat(c).hasNotFailed();try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(c.getBean(SpanExporter.class))).build()){
                var span=provider.get("fincore-test").spanBuilder("observability-probe").startSpan();span.setAttribute("flow","payment_create");span.end();
                provider.forceFlush().join(5,TimeUnit.SECONDS);assertThat(received.get(5,TimeUnit.SECONDS)).isNotEmpty();
                assertThat(new String(received.get(),java.nio.charset.StandardCharsets.ISO_8859_1)).contains("observability-probe","payment_create");
            }});
        }finally{server.stop(0);}
    }
    @Test void propagationAcceptsOnlyTraceparentAndCannotCarryBaggageOrTracestate(){
        context().run(c->{assertThat(c).hasNotFailed();var propagator=c.getBean(ContextPropagators.class).getTextMapPropagator();
            Map<String,String> headers=Map.of("traceparent","00-11112222333344445555666677778888-0123456789abcdef-01","tracestate","private=secret","baggage","private=secret");
            Context extracted=propagator.extract(Context.root(),headers,new TextMapGetter<Map<String,String>>(){public Iterable<String> keys(Map<String,String> m){return m.keySet();}public String get(Map<String,String> m,String k){return m.get(k);}});
            var sc=Span.fromContext(extracted).getSpanContext();assertThat(sc.getTraceId()).isEqualTo("11112222333344445555666677778888");assertThat(sc.getTraceState().isEmpty()).isTrue();
            var outgoing=new HashMap<String,String>();propagator.inject(extracted,outgoing,Map::put);assertThat(outgoing).containsOnlyKeys("traceparent");
        });
    }
}
