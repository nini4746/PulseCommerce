package com.pulse.domain;

public abstract class DomainException extends RuntimeException {

    private final int httpStatus;

    protected DomainException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
