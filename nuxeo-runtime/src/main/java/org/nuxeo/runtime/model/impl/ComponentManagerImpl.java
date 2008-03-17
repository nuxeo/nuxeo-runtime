/*
 * (C) Copyright 2006-2007 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.runtime.model.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.collections.ListenerList;
import org.nuxeo.runtime.ComponentEvent;
import org.nuxeo.runtime.ComponentListener;
import org.nuxeo.runtime.RuntimeService;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.ComponentManager;
import org.nuxeo.runtime.model.ComponentName;
import org.nuxeo.runtime.model.Extension;
import org.nuxeo.runtime.model.RegistrationInfo;
import org.nuxeo.runtime.remoting.RemoteContext;

/**
 * @author  <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 *
 */
public class ComponentManagerImpl implements ComponentManager {

    private static final Log log = LogFactory.getLog(ComponentManager.class);

    protected final Map<ComponentName, Set<Extension>> pendingExtensions;

    private ListenerList listeners;

    private Map<ComponentName, RegistrationInfoImpl> registry;

    private Map<ComponentName, Set<RegistrationInfoImpl>> dependsOnMe;

    private final Map<String, RegistrationInfoImpl> services;


    public ComponentManagerImpl(RuntimeService runtime) {
        registry = new HashMap<ComponentName, RegistrationInfoImpl>();
        dependsOnMe = new HashMap<ComponentName, Set<RegistrationInfoImpl>>();
        pendingExtensions = new HashMap<ComponentName, Set<Extension>>();
        listeners = new ListenerList();
        services = new Hashtable<String, RegistrationInfoImpl>();
    }

    public Collection<RegistrationInfo> getRegistrations() {
        return new ArrayList<RegistrationInfo>(registry.values());
    }

    public Collection<ComponentName> getPendingRegistrations() {
        List<ComponentName> names = new ArrayList<ComponentName>();
        for (RegistrationInfo ri : registry.values()) {
            if (ri.getState() == RegistrationInfo.REGISTERED) {
                names.add(ri.getName());
            }
        }
        return names;
    }

    public Collection<ComponentName> getNeededRegistrations() {
        return pendingExtensions.keySet();
    }

    public Collection<Extension> getPendingExtensions(ComponentName name) {
        return pendingExtensions.get(name);
    }

    public RegistrationInfo getRegistrationInfo(ComponentName name) {
        return registry.get(name);
    }

    public synchronized boolean isRegistered(ComponentName name) {
        return registry.containsKey(name);
    }

    public synchronized int size() {
        return registry.size();
    }

    public ComponentInstance getComponent(ComponentName name) {
        RegistrationInfoImpl ri = registry.get(name);
        return ri != null ? ri.getComponent() : null;
    }

    public synchronized void shutdown() {
        // unregister me -> this will unregister all objects that depends on me
        List<RegistrationInfo> elems = new ArrayList<RegistrationInfo>(
                registry.values());
        for (RegistrationInfo ri : elems) {
            try {
                unregister(ri);
            } catch (Exception e) {
                log.error("failed to shutdown component manager", e);
            }
        }
        try {
            listeners = null;
            registry.clear();
            registry = null;
            dependsOnMe.clear();
            dependsOnMe = null;
        } catch (Exception e) {
            log.error("Failed to shutdown registry manager");
        }
    }

    public synchronized void register(RegistrationInfo regInfo) {
        _register((RegistrationInfoImpl) regInfo);
    }

    public final void _register(RegistrationInfoImpl ri) {
        ComponentName name = ri.getName();
        if (isRegistered(name)) {
            log.warn("Component was already registered: " + name);
            // TODO avoid throwing an exception here - for now runtime components are registered twice
            // When this will be fixed we can thrown an error here
            return;
            //throw new IllegalStateException("Component was already registered: " + name);
        }

        ri.manager = this;

        try {
            ri.register();
        } catch (Exception e) {
            log.error("Failed to register component: " + ri.getName(), e);
            return;
        }

        // compute blocking dependencies
        boolean hasBlockingDeps = computeBlockingDependencies(ri);

        // check if blocking dependencies were found
        if (!hasBlockingDeps) {
            // check if there is any object waiting for me
            Set<RegistrationInfoImpl> pendings = removeDependencies(name);
            // update set the dependsOnMe member
            ri.dependsOnMe = pendings;

            // no blocking dependencies found - register it
            log.info("Registering component: " + ri.getName());
            // create the component
            try {
                registry.put(ri.name, ri);
                ri.resolve();

                // if some objects are waiting for me notify them about my registration
                if (ri.dependsOnMe != null) {
                    // notify all components that deonds on me about my registration
                    for (RegistrationInfoImpl pending : ri.dependsOnMe) {
                        if (pending.waitsFor == null) {
                            _register(pending);
                        } else {
                            // remove object dependence on me
                            pending.waitsFor.remove(name);
                            // if object has no more dependencies register it
                            if (pending.waitsFor.isEmpty()) {
                                pending.waitsFor = null;
                                _register(pending);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Failed to create the component " + ri.name, e);
            }

        } else {
            log.info("Registration delayed for component: " + name
                    + ". Waiting for: " + ri.waitsFor);
        }
    }

    public synchronized void unregister(RegistrationInfo regInfo) {
        _unregister((RegistrationInfoImpl) regInfo);
    }

    public final void _unregister(RegistrationInfoImpl ri) {

        // remove me as a dependent on other objects
        if (ri.requires != null) {
            for (ComponentName dep : ri.requires) {
                RegistrationInfoImpl depRi = registry.get(dep);
                if (depRi != null) { // can be null if comp is unresolved and waiting for this dep.
                    if (depRi.dependsOnMe != null) {
                        depRi.dependsOnMe.remove(ri);
                    }
                }
            }
        }
        // unresolve also the dependent objects
        if (ri.dependsOnMe != null) {
            List<RegistrationInfoImpl> deps = new ArrayList<RegistrationInfoImpl>(
                    ri.dependsOnMe);
            for (RegistrationInfoImpl dep : deps) {
                try {
                    dep.unresolve();
                    // TODO ------------- keep waiting comp. in the registry - otherwise the unresolved comp will never be unregistered
                    // add a blocking dependence on me
                    if (dep.waitsFor == null) {
                        dep.waitsFor = new HashSet<ComponentName>();
                    }
                    dep.waitsFor.add(ri.name);
                    addDependency(ri.name, dep);
                    // remove from registry
                    registry.remove(dep);
                    // TODO -------------
                } catch (Exception e) {
                    log.error("Failed to unresolve component: " + dep.getName(), e);
                }
            }
        }

        log.info("Unregistering component: " + ri.name);

        try {
            if (registry.remove(ri.name) == null) {
                // may be a pending component
                //TODO -> put pendings in the registry
            }
            ri.unregister();
        } catch (Exception e) {
            log.error("Failed to unregister component: " + ri.getName(), e);
        }
    }

    public synchronized void unregister(ComponentName name) {
        RegistrationInfoImpl ri = registry.get(name);
        if (ri != null) {
            _unregister(ri);
        }
    }

    public void addComponentListener(ComponentListener listener) {
        listeners.add(listener);
    }

    public void removeComponentListener(ComponentListener listener) {
        listeners.remove(listener);
    }

    void sendEvent(ComponentEvent event) {
        log.debug("Dispatching event: " + event);
        Object[] listeners = this.listeners.getListeners();
        for (Object listener : listeners) {
            ((ComponentListener) listener).handleEvent(event);
        }
    }

    protected boolean computeBlockingDependencies(RegistrationInfoImpl ri) {
        if (ri.requires != null) {
            for (ComponentName dep : ri.requires) {
                RegistrationInfoImpl depRi = registry.get(dep);
                if (depRi == null) {
                    // dep is not yet registered - add it to the blocking deps queue
                    if (ri.waitsFor == null) {
                        ri.waitsFor = new HashSet<ComponentName>();
                    }
                    ri.waitsFor.add(dep);
                    addDependency(dep, ri);
                } else {
                    // we need this when unregistering depRi
                    // to be able to unregister dependent components
                    if (depRi.dependsOnMe == null) {
                        depRi.dependsOnMe = new HashSet<RegistrationInfoImpl>();
                    }
                    depRi.dependsOnMe.add(ri);
                }
            }
        }
        return ri.waitsFor != null;
    }

    protected synchronized void addDependency(ComponentName name,
            RegistrationInfoImpl dependent) {
        Set<RegistrationInfoImpl> pendings = dependsOnMe.get(name);
        if (pendings == null) {
            pendings = new HashSet<RegistrationInfoImpl>();
            dependsOnMe.put(name, pendings);
        }
        pendings.add(dependent);
    }

    protected synchronized Set<RegistrationInfoImpl> removeDependencies(
            ComponentName name) {
        return dependsOnMe.remove(name);
    }

    public void registerExtension(Extension extension) throws Exception {
        ComponentName name = extension.getTargetComponent();
        RegistrationInfoImpl ri = registry.get(name);
        if (ri != null) {
            if (log.isDebugEnabled()) {
                log.debug("Register contributed extension: " + extension);
            }
            loadContributions(ri, extension);
            ri.component.registerExtension(extension);
            if (!(extension.getContext() instanceof RemoteContext)) {
                // TODO avoid resending events when remoting extensions are registered
                // - temporary hack - find something better
                sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_REGISTERED,
                        ((ComponentInstanceImpl) extension.getComponent()).ri, extension));
            }
        } else { // put the extension in the pending queue
            if (log.isDebugEnabled()) {
                log.debug("Enqueue contributed extension to pending queue: " + extension);
            }
            Set<Extension> extensions = pendingExtensions.get(name);
            if (extensions == null) {
                extensions = new HashSet<Extension>();
                pendingExtensions.put(name, extensions);
            }
            extensions.add(extension);
            if (!(extension.getContext() instanceof RemoteContext)) {
                // TODO avoid resending events when remoting extensions are registered
                // - temporary hack - find something better
                sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_PENDING,
                        ((ComponentInstanceImpl) extension.getComponent()).ri, extension));
            }
        }
    }

    public void unregisterExtension(Extension extension) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Unregister contributed extension: " + extension);
        }
        ComponentName name = extension.getTargetComponent();
        RegistrationInfo ri = registry.get(name);
        if (ri != null) {
            ComponentInstance co = ri.getComponent();
            if (co != null) {
                co.unregisterExtension(extension);
            }
        } else { // maybe it's pending
            Set<Extension> extensions = pendingExtensions.get(name);
            if (extensions != null) {
                // FIXME: extensions is a set of Extensions, not ComponentNames.
                extensions.remove(name);
                if (extensions.isEmpty()) {
                    pendingExtensions.remove(name);
                }
            }
        }
        if (!(extension.getContext() instanceof RemoteContext)) {
            // TODO avoid resending events when remoting extensions are
            // registered - temporary hack - find something better
            sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_UNREGISTERED,
                    ((ComponentInstanceImpl) extension.getComponent()).ri,
                    extension));
        }
    }

    public static void loadContributions(RegistrationInfoImpl ri, Extension xt) {
        ExtensionPointImpl xp = ri.getExtensionPoint(xt.getExtensionPoint());
        if (xp != null && xp.contributions != null) {
            try {
                Object[] contribs = xp.loadContributions(ri, xt);
                xt.setContributions(contribs);
            } catch (Exception e) {
                log.error("Failed to create contribution objects", e);
            }
        }
    }

    public void registerServices(RegistrationInfoImpl ri) {
        if (ri.serviceDescriptor == null) {
            return;
        }
        for (String service : ri.serviceDescriptor.services) {
            log.info("Registering service" + service);
            services.put(service, ri);
            // TODO: send notifications
        }
    }

    public void unregisterServices(RegistrationInfoImpl ri) {
        if (ri.serviceDescriptor == null) {
            return;
        }
        for (String service : ri.serviceDescriptor.services) {
            services.remove(service);
            // TODO: send notifications
        }
    }

    public String[] getServices() {
        return services.keySet().toArray(new String[services.size()]);
    }

    public <T> T getService(Class<T> serviceClass) {
        try {
            RegistrationInfoImpl ri = services.get(serviceClass.getName());
            if (ri == null) {
                log.error("Service unknown: " + serviceClass.getName());
            } else {
                if (!ri.isActivated()) {
                    if (ri.isResolved()) {
                        ri.activate(); // activate the component if not yet activated
                    } else {
                        log.warn("The component exposing the service " + serviceClass
                                + " is not resolved - should be a bug.");
                        return null;
                    }
                }
                return ri.getComponent().getAdapter(serviceClass);
            }
        } catch (Exception e) {
            log.error("Failed to get service: " + serviceClass);
        }
        return null;
    }

}
