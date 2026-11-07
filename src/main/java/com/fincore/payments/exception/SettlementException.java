package com.fincore.payments.exception;
public class SettlementException extends RuntimeException {
    private final int status;
    public SettlementException(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
}
