package com.leaveapp.model.form;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ForgotPwdForm {

    /* Variables */

    private String orgUsername;
    private String email;

    // reCAPTCHA v3 token from the frontend widget -- see CaptchaService. Ignored entirely
    // when app.captcha.enabled=false.
    private String captchaToken;
}
