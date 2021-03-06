/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket.server;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

/**
 * Registers an interest in any class that is annotated with
 * {@link ServerEndpoint} so that Endpoint can be published via the WebSocket
 * server.
 */
@HandlesTypes({ServerEndpoint.class, ServerEndpointConfig.class,
        ServerApplicationConfig.class})
public class WsSci implements ServletContainerInitializer {

    @Override
    public void onStartup(Set<Class<?>> clazzes, ServletContext ctx)
            throws ServletException {

        ctx.addListener(WsListener.class);

        if (clazzes == null || clazzes.size() == 0) {
            return;
        }

        // Group the discovered classes by type
        Set<ServerApplicationConfig> serverApplicationConfigs = new HashSet<>();
        Set<ServerEndpointConfig> scannedEndpointConfigs = new HashSet<>();
        Set<Class<? extends Endpoint>> scannedEndpointClazzes = new HashSet<>();
        Set<Class<?>> scannedPojoEndpoints = new HashSet<>();

        try {
            for (Class<?> clazz : clazzes) {
                // Protect against scanning the WebSocket API JARs
                if (clazz.getPackage().getName().startsWith(
                        ContainerProvider.class.getPackage().getName())) {
                    continue;
                }
                if (ServerApplicationConfig.class.isAssignableFrom(clazz)) {
                    serverApplicationConfigs.add(
                            (ServerApplicationConfig) clazz.newInstance());
                }
                if (ServerEndpointConfig.class.isAssignableFrom(clazz)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends ServerEndpointConfig> configClazz =
                            (Class<? extends ServerEndpointConfig>) clazz;
                    ServerEndpointConfig config = configClazz.newInstance();
                    scannedEndpointConfigs.add(config);
                    scannedEndpointClazzes.add(
                            (Class<? extends Endpoint>) config.getEndpointClass());
                }
                if (clazz.isAnnotationPresent(ServerEndpoint.class)) {
                    scannedPojoEndpoints.add(clazz);
                }
            }
        } catch (InstantiationException | IllegalAccessException e) {
            throw new ServletException(e);
        }

        // Filter the results
        Set<ServerEndpointConfig> filteredEndpointConfigs = new HashSet<>();
        Set<Class<?>> filteredPojoEndpoints = new HashSet<>();

        if (serverApplicationConfigs.isEmpty()) {
            filteredEndpointConfigs.addAll(scannedEndpointConfigs);
            filteredPojoEndpoints.addAll(scannedPojoEndpoints);
        } else {
            for (ServerApplicationConfig config : serverApplicationConfigs) {
                filteredEndpointConfigs.addAll(
                        config.getEndpointConfigs(scannedEndpointClazzes));
            }
        }

        WsServerContainer sc = WsServerContainer.getServerContainer();
        sc.setServletContext(ctx);
        try {
            // Deploy endpoints
            for (ServerEndpointConfig config : filteredEndpointConfigs) {
                sc.addEndpoint(config);
            }
            // Deploy POJOs
            for (Class<?> clazz : filteredPojoEndpoints) {
                sc.addEndpoint(clazz);
            }
        } catch (DeploymentException e) {
            throw new ServletException(e);
        }
    }
}
