package com.leaveapp.model.form;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetPasswordForm {

    /* Variables */

    private String token;

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] newPassword = new byte[0];
}
