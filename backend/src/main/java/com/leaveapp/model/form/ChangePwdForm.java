package com.leaveapp.model.form;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePwdForm {

    /* Variables */

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] currentPassword = new byte[0];

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] newPassword = new byte[0];
}
