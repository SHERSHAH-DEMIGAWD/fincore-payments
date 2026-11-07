package com.fincore.payments.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.net.URI;

@Validated
@ConfigurationProperties(prefix="fincore.observability")
public record ObservabilityProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String username,@DefaultValue("") String passwordHash,
        @DefaultValue("true") boolean collectorEnabled,@DefaultValue("15000") long snapshotIntervalMillis,
        @DefaultValue("false") boolean traceExportEnabled,
        @DefaultValue("http://127.0.0.1:4318/v1/traces") URI traceEndpoint) {
    public boolean configured(){return enabled && username!=null && !username.isEmpty() && passwordHash!=null && !passwordHash.isEmpty();}
    @AssertTrue(message="Enabled observability requires bounded settings, paired observer credentials and a loopback trace endpoint.")
    public boolean isValidConfiguration(){
        if(!enabled)return true;
        boolean credentials=username!=null && passwordHash!=null && username.isEmpty()==passwordHash.isEmpty();
        if(credentials && !username.isEmpty())credentials=username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
            && passwordHash.matches("\\$2[aby]\\$(?:0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}");
        return credentials && snapshotIntervalMillis>=5000 && snapshotIntervalMillis<=300000
            && traceEndpoint!=null && "http".equals(traceEndpoint.getScheme()) && "127.0.0.1".equals(traceEndpoint.getHost())
            && traceEndpoint.getUserInfo()==null && traceEndpoint.getQuery()==null && traceEndpoint.getFragment()==null
            && traceEndpoint.getPort()>0 && traceEndpoint.getPort()<=65535 && "/v1/traces".equals(traceEndpoint.getPath());
    }
    @Override public String toString(){return "ObservabilityProperties[enabled="+enabled+",credentials=REDACTED]";}
}
