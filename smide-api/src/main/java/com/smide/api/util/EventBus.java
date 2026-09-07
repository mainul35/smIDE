package com.smide.api.util;

import java.util.function.Consumer;

/** Typed publish/subscribe. Handlers run on the JavaFX thread. */
public interface EventBus {

    <E> Subscription subscribe(Class<E> type, Consumer<E> handler);

    void publish(Object event);

    interface Subscription {
        void cancel();
    }
}
