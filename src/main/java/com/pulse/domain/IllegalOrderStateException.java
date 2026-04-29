package com.pulse.domain;

public class IllegalOrderStateException extends DomainException {
    public IllegalOrderStateException(String message) {
        super(409, message);
    }
}
