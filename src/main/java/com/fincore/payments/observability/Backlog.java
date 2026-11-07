package com.fincore.payments.observability;

public enum Backlog {
    OUTBOX_PENDING("outbox","pending"),OUTBOX_DEAD("outbox","dead"),
    PROVIDER_UNKNOWN("provider","unknown"),PROVIDER_REVIEW("provider","review"),PROVIDER_EXPIRED("provider","lease_expired"),
    WEBHOOK_PENDING("merchant_webhook","pending"),WEBHOOK_DEAD("merchant_webhook","dead"),WEBHOOK_EXPIRED("merchant_webhook","lease_expired"),
    CONSUMER_UNRESOLVED("consumer","unresolved"),SETTLEMENT_UNRESOLVED("settlement","unresolved_report");
    public final String component,state;
    Backlog(String component,String state){this.component=component;this.state=state;}
    public record Point(long count,double oldestAgeSeconds){public Point{
        if(count<0 || !Double.isFinite(oldestAgeSeconds) || oldestAgeSeconds<0)throw new IllegalArgumentException("Invalid operational snapshot.");}}
}
