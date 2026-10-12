package com.leaveapp.exception;

public class BadRequestException extends RuntimeException {

    /* Constructors */

    public BadRequestException(String msg) {
        super(msg);
    }
}
