package com.example.myagent.incident.application.domain.service.internal;

import com.example.myagent.global.annotation.InternalService;
import io.vavr.control.Try;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import org.springframework.core.task.TaskExecutor;

@InternalService
public class HotfixExecutionRegistry {
    private final Map<String, FutureTask<Void>> activeTasks = new HashMap<>();
    private final Set<String> cancelledHotfixes = new HashSet<>();

    public synchronized void submit(
        String hotfixId,
        Runnable action,
        TaskExecutor taskExecutor
    ) {
        if (activeTasks.containsKey(hotfixId)) {
            return;
        }
        var task = new FutureTask<Void>(() -> {
            Try.run(action::run).andFinally(() -> complete(hotfixId)).get();
            return null;
        });
        activeTasks.put(hotfixId, task);
        Try.run(() -> taskExecutor.execute(task))
            .onFailure(exception -> complete(hotfixId));
    }

    public synchronized void runIfActive(String hotfixId, Runnable action) {
        if (!cancelledHotfixes.contains(hotfixId)) {
            action.run();
        }
    }

    public synchronized void cancel(String hotfixId) {
        cancelledHotfixes.add(hotfixId);
        var task = activeTasks.remove(hotfixId);
        if (task != null) {
            task.cancel(true);
        }
    }

    public synchronized boolean isCancelled(String hotfixId) {
        return cancelledHotfixes.contains(hotfixId);
    }

    private synchronized void complete(String hotfixId) {
        activeTasks.remove(hotfixId);
    }
}
