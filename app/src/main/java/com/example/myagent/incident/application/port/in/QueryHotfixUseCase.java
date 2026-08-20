package com.example.myagent.incident.application.port.in;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;

public interface QueryHotfixUseCase {
    HotfixResource getHotfix(String hotfixId);

    HotfixResource refreshCiStatus(String hotfixId);
}
