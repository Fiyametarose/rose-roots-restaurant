package com.roseroots.server.admin;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertSame;

class AdminControllerTest {
    @Test
    void dashboardUsesTheAuthoritativeSnapshotWhenAProviderIsRegistered() {
        var expected = new DashboardSnapshot(1L, 0L, null, null,
                List.of(new DashboardSnapshot.Order("12:00", "test-order", "Takeaway", "Tea", null, "Ready")),
                List.of(), List.of("Test alert"));
        var beans = new StaticListableBeanFactory();
        beans.addBean("dashboard", (DashboardSource) () -> expected);

        assertSame(expected, new AdminController(beans.getBeanProvider(DashboardSource.class)).dashboard());
    }
}
