package com.pulse.domain;

public class InsufficientStockException extends DomainException {
    public InsufficientStockException(String message) {
        super(409, message);
    }
}
