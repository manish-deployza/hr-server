package com.leaveapp.model.form;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginForm {

    /* Variables */

    private String orgKey;
    private String email;

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] password = new byte[0];

    // reCAPTCHA v3 token from the frontend widget -- see CaptchaService. Ignored entirely
    // when app.captcha.enabled=false.
    private String captchaToken;
}
