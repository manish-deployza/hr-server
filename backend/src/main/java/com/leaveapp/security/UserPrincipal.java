package com.leaveapp.security;

import java.util.List;

import com.leaveapp.pojo.UserRole;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserPrincipal {

    /* Variables */

    private Long userId;
    private Long organizationId;
    private List<UserRole> roles;
}
