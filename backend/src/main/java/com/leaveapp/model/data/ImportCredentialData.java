package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// One created row's login credential, shown to the admin exactly once in the import
// response -- never persisted or logged as plaintext.
@Getter
@Setter
public class ImportCredentialData {

    /* Variables */

    private int row;
    private String email;
    private String tempPassword;
}
