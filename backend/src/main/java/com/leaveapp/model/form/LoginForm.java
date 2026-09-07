package com.leaveapp.model.form;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginForm {

    /* Variables */

    private String orgUsername;
    private String email;

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] password = new byte[0];
}
