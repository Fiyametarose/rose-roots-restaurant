package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.AssistanceRequest;
import java.util.List;

public interface AssistanceService {
    List<AssistanceRequest> requests();

    void acknowledge(long requestId);

    void resolve(long requestId);
}
