package com.roseroots.server.admin;

/** Implement with the authoritative restaurant services when they are available. */
public interface DashboardSource {
    DashboardSnapshot snapshot();
}
