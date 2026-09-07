package com.leaveapp.model.data;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorData {

    /* Variables */

    private String summary;
    private String details;
    private List<String> stackTrace;

    /* Constructors */

    public ErrorData(String summary) {
        this.summary = summary;
    }
}
