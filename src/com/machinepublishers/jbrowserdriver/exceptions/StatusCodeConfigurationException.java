package com.machinepublishers.jbrowserdriver.exceptions;

/**
 * Created by alompo on 06.11.17.
 */
public class StatusCodeConfigurationException extends RuntimeException {
    public StatusCodeConfigurationException(Exception ex) {
        super(ex);
    }

    public StatusCodeConfigurationException(String message) {
        super(message);
    }
}
