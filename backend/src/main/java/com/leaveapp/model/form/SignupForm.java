package com.leaveapp.model.form;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupForm {

    /* Variables */

    // orgName/name deliberately not collected at signup -- less friction; set later
    // via settings (not yet built). See prompts/features/multi-tenancy.md.
    private String orgUsername;
    private String email;

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] password = new byte[0];

    // reCAPTCHA v3 token from the frontend widget -- see CaptchaService. Ignored entirely
    // when app.captcha.enabled=false.
    private String captchaToken;
}
