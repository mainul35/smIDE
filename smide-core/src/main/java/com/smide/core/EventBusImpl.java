package com.smide.core;

import com.smide.api.util.EventBus;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class EventBusImpl implements EventBus {

    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <E> Subscription subscribe(Class<E> type, Consumer<E> handler) {
        Consumer<Object> wrapped = o -> handler.accept((E) o);
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(wrapped);
        return () -> {
            List<Consumer<Object>> list = handlers.get(type);
            if (list != null) {
                list.remove(wrapped);
            }
        };
    }

    @Override
    public void publish(Object event) {
        if (event == null) {
            return;
        }
        Runnable dispatch = () -> {
            List<Consumer<Object>> targets = new ArrayList<>();
            for (Map.Entry<Class<?>, List<Consumer<Object>>> e : handlers.entrySet()) {
                if (e.getKey().isInstance(event)) {
                    targets.addAll(e.getValue());
                }
            }
            for (Consumer<Object> h : targets) {
                try {
                    h.accept(event);
                } catch (RuntimeException ex) {
                    System.err.println("smIDE: event handler failed for " + event + ": " + ex);
                    ex.printStackTrace();
                }
            }
        };
        if (Platform.isFxApplicationThread()) {
            dispatch.run();
        } else {
            Platform.runLater(dispatch);
        }
    }
}
