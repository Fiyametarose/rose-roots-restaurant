package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.StaffMember;

public interface RushAuthorization {
    StaffMember requireAuthorizer();
}
