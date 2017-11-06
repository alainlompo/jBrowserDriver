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

    int computeIdleCount(int size, int idleCount) {
        int newValue = idleCount;
        if (size == 0) {
            ++newValue;
        } else {
            newValue = 0;
        }
        return newValue;
    }

    ResourcesSizeStatus updateResourcesSizeInTime(final Settings settings) {
        long time = System.currentTimeMillis();
        int size = harmonizeResources(time, settings);
        return new ResourcesSizeStatus(time, size);
    }

    ResourcesStatus handleResource(final long sleepMS, final int idleCountValue, final int idleCountTarget, final Settings settings) {

        ResourcesStatus status = new ResourcesStatus();
        try {
            Thread.sleep(sleepMS);
        } catch (InterruptedException e) {
            LOGGER.log(Level.FINE, "An attempt to put a thread to sleep generated an exception ", e);
            status.setHandlingStatus(ResourceHandlingStatus.INVALID);
            return status;
        }

        ResourcesSizeStatus sizeStatus = updateResourcesSizeInTime(settings);
        status.setSizeStatus(sizeStatus);

        if (!sizeStatus.isSizeprovided()) {
            LOGGER.log(Level.FINE, "Invalid resources size: check underlying issue ");
            status.setHandlingStatus(ResourceHandlingStatus.INVALID);
            return status;
        }

        int size = sizeStatus.getSize();

        int idleCount = computeIdleCount(size, idleCountValue);
        status.setIdleCount(idleCount);

        if (idleCount == idleCountTarget) {
            status.setHandlingStatus(ResourceHandlingStatus.REACHED_IDLE_COUNT_TARGET);
            return status;
        }

        status.setHandlingStatus(ResourceHandlingStatus.VALID);
        return status;
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

                    ResourcesStatus resourcesStatus = handleResource(sleepMS, idleCount, idleCountTarget, settings );
                    if (resourcesStatus.getHandlingStatus() == ResourceHandlingStatus.INVALID) {
                        return;
                    }

                    time = resourcesStatus.getSizeStatus().getTime();
                    size = resourcesStatus.getSizeStatus().getSize();

                    idleCount = resourcesStatus.getIdleCount();
                    if (resourcesStatus.getHandlingStatus() == ResourceHandlingStatus.REACHED_IDLE_COUNT_TARGET) {
                        break;
                    }
                }
            }

            try {
                configureStatusCode();
            } catch (StatusCodeConfigurationException ex) {
                LOGGER.log(Level.FINE, "A problem related to code status configuration occured", ex);
                return;
            }
        }
    }

    class ResourcesSizeStatus {
        private long time;
        private int size;

        public ResourcesSizeStatus(long time, int size) {
            this.time = time;
            this.size = size;
        }

        public boolean isSizeprovided() {
            return size != -1;
        }

        public long getTime() {
            return time;
        }

        public int getSize() {
            return size;
        }
    }

    class ResourcesStatus {
        private ResourcesSizeStatus sizeStatus;
        private int idleCount;
        private ResourceHandlingStatus handlingStatus;

        public ResourcesStatus() {
        }

        public ResourcesStatus(ResourcesSizeStatus sizeStatus, int idleCount) {
            this.sizeStatus = sizeStatus;
            this.idleCount = idleCount;
        }

        public ResourcesSizeStatus getSizeStatus() {
            return sizeStatus;
        }

        public int getIdleCount() {
            return idleCount;
        }

        public void setSizeStatus(ResourcesSizeStatus sizeStatus) {
            this.sizeStatus = sizeStatus;
        }

        public void setIdleCount(int idleCount) {
            this.idleCount = idleCount;
        }

        public ResourceHandlingStatus getHandlingStatus() {
            return handlingStatus;
        }

        public void setHandlingStatus(ResourceHandlingStatus handlingStatus) {
            this.handlingStatus = handlingStatus;
        }
    }

    enum ResourceHandlingStatus {
        INVALID,
        REACHED_IDLE_COUNT_TARGET,
        VALID
    }
}