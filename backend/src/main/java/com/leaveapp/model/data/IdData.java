package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// { "id": ... } response for create endpoints -- replaces the old Map.of("id", id).
@Getter
@Setter
public class IdData {

    /* Variables */

    private Long id;
}
