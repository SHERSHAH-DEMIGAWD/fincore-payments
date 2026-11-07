package com.fincore.payments.observability;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class TraceCorrelationFilter extends OncePerRequestFilter {
    private final Tracer tracer;
    public TraceCorrelationFilter(Tracer tracer){this.tracer=tracer;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        var span=tracer.currentSpan();if(span!=null){String id=span.context().traceId();
            if(id.matches("[0-9a-f]{32}"))response.setHeader("X-Fincore-Trace-Id",id);}
        chain.doFilter(request,response);
    }
}
