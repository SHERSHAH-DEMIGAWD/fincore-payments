package com.fincore.payments.exception;
import java.util.UUID;
public class PaymentRiskException extends RuntimeException {
    private final int status;private final String code;private final UUID decisionId;private final Long retrySeconds;
    public PaymentRiskException(int status,String code,String message,UUID decisionId,Long retrySeconds){super(message);this.status=status;this.code=code;this.decisionId=decisionId;this.retrySeconds=retrySeconds;}
    public int status(){return status;}public String code(){return code;}public UUID decisionId(){return decisionId;}public Long retrySeconds(){return retrySeconds;}
}
