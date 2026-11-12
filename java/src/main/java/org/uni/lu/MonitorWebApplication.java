package org.uni.lu;

import org.onlab.rest.AbstractWebApplication;

import java.util.Set;

/**
 * JAX-RS application wiring the monitor's single REST resource into ONOS's
 * web container.
 */
public class MonitorWebApplication extends AbstractWebApplication {
    @Override
    public Set<Class<?>> getClasses() {
        return getClasses(MonitorWebResource.class);
    }
}
