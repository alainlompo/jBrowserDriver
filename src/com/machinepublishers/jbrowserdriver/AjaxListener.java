/* 
 * jBrowserDriver (TM)
 * Copyright (C) 2014-2016 jBrowserDriver committers
 * https://github.com/MachinePublishers/jBrowserDriver
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.machinepublishers.jbrowserdriver;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.machinepublishers.jbrowserdriver.exceptions.StatusCodeConfigurationException;

import javafx.application.Platform;

class AjaxListener implements Runnable {

    private static final long MAX_WAIT_DEFAULT = 15000;
    private static final int IDLE_COUNT_TARGET = 3;

    private static final Logger LOGGER = Logger.getLogger(AjaxListener.class.getName());

    private final AtomicBoolean started;
    private final AtomicInteger newStatusCode;
    private final StatusCode statusCode;
    private final Map<String, Long> resources;
    private final AtomicLong timeoutMS;

    AjaxListener(final AtomicBoolean started, final AtomicInteger newStatusCode,
                 final StatusCode statusCode,
                 final Map<String, Long> resources, final AtomicLong timeoutMS) {
        this.started = started;
        this.newStatusCode = newStatusCode;
        this.statusCode = statusCode;
        this.resources = resources;
        this.timeoutMS = timeoutMS;
    }

    synchronized void harmonizeStatusCode() {
        while (statusCode.get() != 0) {
            try {
                statusCode.wait();
            } catch (InterruptedException e) {
            }
        }
    }

    synchronized void harmonize(final AtomicBoolean done) {
        done.set(true);
        done.notifyAll();
    }

    synchronized int harmonizeResources(final long time, final Settings settings) {

        if (Thread.interrupted()) {
            return -1;
        }
        final Set<String> remove = new HashSet<String>();
        for (Map.Entry<String, Long> entry : resources.entrySet()) {
            if (time - entry.getValue() > settings.ajaxResourceTimeout()) {
                remove.add(entry.getKey());
            }
        }
        for (String key : remove) {
            resources.remove(key);
        }
        return resources.size();
    }

    synchronized void configureStatusCode() {
        if (Thread.interrupted()) {
            throw new StatusCodeConfigurationException("Status code configuration failed on thread synchronization");
        }
        int newStatusCodeVal = newStatusCode.getAndSet(0);
        newStatusCodeVal = newStatusCodeVal <= 0 ? (started.get() ? 0 : 200) : newStatusCodeVal;
        resources.clear();
        StatusMonitor.instance().clear();
        statusCode.set(newStatusCodeVal);
        statusCode.notifyAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        while (true) {
            harmonizeStatusCode();
            int size = 0;
            final long start = System.currentTimeMillis();
            long time = start;
            final Settings settings = SettingsManager.settings();
            final AtomicBoolean done = new AtomicBoolean();
            Platform.runLater(() -> harmonize(done));
            if (settings != null) {
                final long sleepMS = Math.max(settings.ajaxWait() / IDLE_COUNT_TARGET, 0);
                int idleCount = 0;
                int idleCountTarget = sleepMS == 0 ? 1 : IDLE_COUNT_TARGET;

                while (time - start < (timeoutMS.get() <= 0 ? MAX_WAIT_DEFAULT : timeoutMS.get())) {
                    try {
                        Thread.sleep(sleepMS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    time = System.currentTimeMillis();
                    size = harmonizeResources(time, settings);
                    if (size == -1) {
                        return;
                    }

                    if (size == 0) {
                        ++idleCount;
                    } else {
                        idleCount = 0;
                    }
                    if (idleCount == idleCountTarget) {
                        break;
                    }
                }
            }

            try {
                configureStatusCode();
            } catch (StatusCodeConfigurationException ex) {
                LOGGER.log(Level.FINE, "A problem related to code status configuration occured", ex);
            }
        }
    }
}