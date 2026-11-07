package com.fincore.payments.provider;

/** Transport/protocol failure cannot establish that the provider did nothing. */
public class ProviderUncertainException extends Exception {
    public ProviderUncertainException() { super("Provider outcome could not be confirmed."); }
}
