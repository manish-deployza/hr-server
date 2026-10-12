package com.leaveapp.exception;

import org.springframework.security.core.AuthenticationException;

public class NotAuthException extends AuthenticationException {

    /* Constructors */

    public NotAuthException(String msg) {
        super(msg);
    }
}
