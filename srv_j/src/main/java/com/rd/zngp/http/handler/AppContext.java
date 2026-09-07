package com.rd.zngp.http.handler;

import com.rd.zngp.store.Store;

/**
 * Provides access to the global Store instance for handlers.
 * Set once during server startup.
 */
public class AppContext {

    private static volatile Store store;

    private AppContext() {}

    public static void setStore(Store s) {
        store = s;
    }

    public static Store getStore() {
        return store;
    }
}