/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains reusable browser instances for callers that opt into sharing. */
public class BrowserPoolManager {
    private static final int MAX_POOL_SIZE = 50;
    private static final long BROWSER_TIMEOUT_MS = 300_000;

    private final Map<String, PooledBrowser> browserCache = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Long> lastAccessTime = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized MCEFBrowser getBrowser(String url, boolean transparent, boolean shared, Object owner) {
        String cacheKey = getCacheKey(url, transparent);
        PooledBrowser pooledBrowser = browserCache.get(cacheKey);
        if (pooledBrowser != null && pooledBrowser.shared && shared) {
            pooledBrowser.owners.add(owner);
            lastAccessTime.put(cacheKey, System.currentTimeMillis());
            MCEF.LOGGER.info("Reusing browser from pool for URL: {} (owners: {})", url, pooledBrowser.owners.size());
            return pooledBrowser.browser;
        }

        MCEFBrowser browser = MCEF.createBrowser(url, transparent);
        if (pooledBrowser != null) {
            cacheKey = cacheKey + "|" + System.nanoTime();
        }
        pooledBrowser = new PooledBrowser(browser, shared);
        pooledBrowser.owners.add(owner);
        browserCache.put(cacheKey, pooledBrowser);
        lastAccessTime.put(cacheKey, System.currentTimeMillis());
        MCEF.LOGGER.info("Created new browser for URL: {} (pool size: {})", url, browserCache.size());
        if (browserCache.size() > MAX_POOL_SIZE) {
            cleanup();
        }
        return browser;
    }

    public synchronized void releaseBrowser(MCEFBrowser browser, Object owner) {
        for (Map.Entry<String, PooledBrowser> entry : browserCache.entrySet()) {
            PooledBrowser pooledBrowser = entry.getValue();
            if (pooledBrowser.browser != browser) {
                continue;
            }
            pooledBrowser.owners.remove(owner);
            lastAccessTime.put(entry.getKey(), System.currentTimeMillis());
            MCEF.LOGGER.info("Released browser (remaining owners: {})", pooledBrowser.owners.size());
            if (pooledBrowser.owners.isEmpty() && !pooledBrowser.shared) {
                browserCache.remove(entry.getKey());
                lastAccessTime.remove(entry.getKey());
                browser.close(true);
                MCEF.LOGGER.info("Closed non-shared browser with no owners");
            }
            return;
        }
        browser.close(true);
    }

    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastAccessTime.entrySet()) {
            PooledBrowser pooledBrowser = browserCache.get(entry.getKey());
            if (pooledBrowser != null && pooledBrowser.owners.isEmpty()
                    && now - entry.getValue() > BROWSER_TIMEOUT_MS) {
                expiredKeys.add(entry.getKey());
            }
        }
        for (String key : expiredKeys) {
            PooledBrowser pooledBrowser = browserCache.remove(key);
            lastAccessTime.remove(key);
            if (pooledBrowser != null) {
                pooledBrowser.browser.close(true);
                MCEF.LOGGER.info("Cleaned up unused browser from pool");
            }
        }
        if (!expiredKeys.isEmpty()) {
            MCEF.LOGGER.info("Pool cleanup: removed {} browsers (pool size: {})",
                    expiredKeys.size(), browserCache.size());
        }
    }

    public synchronized void shutdown() {
        MCEF.LOGGER.info("Shutting down browser pool ({} browsers)", browserCache.size());
        for (PooledBrowser pooledBrowser : browserCache.values()) {
            pooledBrowser.browser.close(true);
        }
        browserCache.clear();
        lastAccessTime.clear();
    }

    private String getCacheKey(String url, boolean transparent) {
        return url + "|" + transparent;
    }

    private static final class PooledBrowser {
        private final MCEFBrowser browser;
        private final Set<Object> owners = Collections.synchronizedSet(new HashSet<>());
        private final boolean shared;

        private PooledBrowser(MCEFBrowser browser, boolean shared) {
            this.browser = browser;
            this.shared = shared;
        }
    }
}
