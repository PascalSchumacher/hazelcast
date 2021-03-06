/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.config;

import com.hazelcast.client.util.RandomLB;
import com.hazelcast.client.util.RoundRobinLB;
import com.hazelcast.config.AbstractConfigBuilder;
import com.hazelcast.config.ConfigLoader;
import com.hazelcast.config.DiscoveryStrategyConfig;
import com.hazelcast.config.DiscoveryConfig;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.InvalidConfigurationException;
import com.hazelcast.config.ListenerConfig;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.config.SSLConfig;
import com.hazelcast.config.SerializationConfig;
import com.hazelcast.config.SocketInterceptorConfig;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.util.StringUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.hazelcast.client.config.ClientXmlElements.EXECUTOR_POOL_SIZE;
import static com.hazelcast.client.config.ClientXmlElements.GROUP;
import static com.hazelcast.client.config.ClientXmlElements.INSTANCE_NAME;
import static com.hazelcast.client.config.ClientXmlElements.LICENSE_KEY;
import static com.hazelcast.client.config.ClientXmlElements.LISTENERS;
import static com.hazelcast.client.config.ClientXmlElements.LOAD_BALANCER;
import static com.hazelcast.client.config.ClientXmlElements.NATIVE_MEMORY;
import static com.hazelcast.client.config.ClientXmlElements.NEAR_CACHE;
import static com.hazelcast.client.config.ClientXmlElements.NETWORK;
import static com.hazelcast.client.config.ClientXmlElements.PROPERTIES;
import static com.hazelcast.client.config.ClientXmlElements.PROXY_FACTORIES;
import static com.hazelcast.client.config.ClientXmlElements.QUERY_CACHES;
import static com.hazelcast.client.config.ClientXmlElements.SECURITY;
import static com.hazelcast.client.config.ClientXmlElements.SERIALIZATION;
import static com.hazelcast.client.config.ClientXmlElements.canOccurMultipleTimes;
import static com.hazelcast.util.StringUtil.upperCaseInternal;

/**
 * Loads the {@link com.hazelcast.client.config.ClientConfig} using XML.
 */
public class XmlClientConfigBuilder extends AbstractConfigBuilder {

    private static final ILogger LOGGER = Logger.getLogger(XmlClientConfigBuilder.class);

    private Properties properties = System.getProperties();

    private ClientConfig clientConfig;
    private Set<String> occurrenceSet = new HashSet<String>();
    private InputStream in;
    private final QueryCacheConfigBuilderHelper queryCacheConfigBuilderHelper = new QueryCacheConfigBuilderHelper();

    public XmlClientConfigBuilder(String resource) throws IOException {
        URL url = ConfigLoader.locateConfig(resource);
        if (url == null) {
            throw new IllegalArgumentException("Could not load " + resource);
        }
        this.in = url.openStream();
    }

    public XmlClientConfigBuilder(File file) throws IOException {
        if (file == null) {
            throw new NullPointerException("File is null!");
        }
        in = new FileInputStream(file);
    }

    public XmlClientConfigBuilder(URL url) throws IOException {
        if (url == null) {
            throw new NullPointerException("URL is null!");
        }
        in = url.openStream();
    }

    public XmlClientConfigBuilder(InputStream in) {
        this.in = in;
    }

    /**
     * Loads the client config using the following resolution mechanism:
     * <ol>
     * <li>first it checks if a system property 'hazelcast.client.config' is set. If it exist and it begins with
     * 'classpath:', then a classpath resource is loaded. Else it will assume it is a file reference</li>
     * <li>it checks if a hazelcast-client.xml is available in the working dir</li>
     * <li>it checks if a hazelcast-client.xml is available on the classpath</li>
     * <li>it loads the hazelcast-client-default.xml</li>
     * </ol>
     */
    public XmlClientConfigBuilder() {
        XmlClientConfigLocator locator = new XmlClientConfigLocator();
        this.in = locator.getIn();
    }

    @Override
    protected Document parse(InputStream inputStream) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final DocumentBuilder builder = dbf.newDocumentBuilder();
        try {
            return builder.parse(inputStream);
        } catch (final Exception e) {
            String lineSeparator = StringUtil.getLineSeperator();
            String msg = "Failed to parse Config Stream"
                    + lineSeparator + "Exception: " + e.getMessage()
                    + lineSeparator + "HazelcastClient startup interrupted.";
            LOGGER.severe(msg);
            throw new InvalidConfigurationException(e.getMessage(), e);
        } finally {
            IOUtil.closeResource(inputStream);
        }
    }

    @Override
    protected Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    @Override
    protected ConfigType getXmlType() {
        return ConfigType.CLIENT;
    }

    public ClientConfig build() {
        return build(Thread.currentThread().getContextClassLoader());
    }

    public ClientConfig build(ClassLoader classLoader) {
        final ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClassLoader(classLoader);
        try {
            parseAndBuildConfig(clientConfig);
            return clientConfig;
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        } finally {
            IOUtil.closeResource(in);
        }
    }

    private void parseAndBuildConfig(ClientConfig clientConfig) throws Exception {
        this.clientConfig = clientConfig;
        Document doc = parse(in);
        Element root = doc.getDocumentElement();
        try {
            root.getTextContent();
        } catch (final Throwable e) {
            domLevel3 = false;
        }
        process(root);
        schemaValidation(root.getOwnerDocument());
        handleConfig(root);
    }

    private void handleConfig(final Element docElement) throws Exception {
        for (Node node : childElements(docElement)) {
            final String nodeName = cleanNodeName(node);
            if (occurrenceSet.contains(nodeName)) {
                throw new InvalidConfigurationException("Duplicate '" + nodeName + "' definition found in XML configuration. ");
            }
            handleXmlNode(node, nodeName);
            if (!canOccurMultipleTimes(nodeName)) {
                occurrenceSet.add(nodeName);
            }
        }
    }

    private void handleXmlNode(Node node, String nodeName) throws Exception {
        if (SECURITY.isEqual(nodeName)) {
            handleSecurity(node);
        } else if (PROXY_FACTORIES.isEqual(nodeName)) {
            handleProxyFactories(node);
        } else if (PROPERTIES.isEqual(nodeName)) {
            fillProperties(node, clientConfig.getProperties());
        } else if (SERIALIZATION.isEqual(nodeName)) {
            handleSerialization(node);
        } else if (NATIVE_MEMORY.isEqual(nodeName)) {
            fillNativeMemoryConfig(node, clientConfig.getNativeMemoryConfig());
        } else if (GROUP.isEqual(nodeName)) {
            handleGroup(node);
        } else if (LISTENERS.isEqual(nodeName)) {
            handleListeners(node);
        } else if (NETWORK.isEqual(nodeName)) {
            handleNetwork(node);
        } else if (LOAD_BALANCER.isEqual(nodeName)) {
            handleLoadBalancer(node);
        } else if (NEAR_CACHE.isEqual(nodeName)) {
            handleNearCache(node);
        } else if (QUERY_CACHES.isEqual(nodeName)) {
            queryCacheConfigBuilderHelper.handleQueryCache(clientConfig, node);
        } else if (EXECUTOR_POOL_SIZE.isEqual(nodeName)) {
            handleExecutorPoolSize(node);
        } else if (LICENSE_KEY.isEqual(nodeName)) {
            clientConfig.setLicenseKey(getTextContent(node));
        } else if (INSTANCE_NAME.isEqual(nodeName)) {
            clientConfig.setInstanceName(getTextContent(node));
        }
    }

    private void handleExecutorPoolSize(Node node) {
        final int poolSize = Integer.parseInt(getTextContent(node));
        clientConfig.setExecutorPoolSize(poolSize);
    }

    private void handleNearCache(Node node) {
        final String name = getAttribute(node, "name");
        final NearCacheConfig nearCacheConfig = new NearCacheConfig();
        for (Node child : childElements(node)) {
            final String nodeName = cleanNodeName(child);
            String value = getTextContent(child).trim();
            if ("max-size".equals(nodeName)) {
                nearCacheConfig.setMaxSize(Integer.parseInt(value));
            } else if ("time-to-live-seconds".equals(nodeName)) {
                nearCacheConfig.setTimeToLiveSeconds(Integer.parseInt(value));
            } else if ("max-idle-seconds".equals(nodeName)) {
                nearCacheConfig.setMaxIdleSeconds(Integer.parseInt(value));
            } else if ("eviction-policy".equals(nodeName)) {
                nearCacheConfig.setEvictionPolicy(value);
            } else if ("in-memory-format".equals(nodeName)) {
                nearCacheConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
            } else if ("invalidate-on-change".equals(nodeName)) {
                nearCacheConfig.setInvalidateOnChange(Boolean.parseBoolean(value));
            } else if ("cache-local-entries".equals(nodeName)) {
                nearCacheConfig.setCacheLocalEntries(Boolean.parseBoolean(value));
            } else if ("local-update-policy".equals(nodeName)) {
                NearCacheConfig.LocalUpdatePolicy policy = NearCacheConfig.LocalUpdatePolicy.valueOf(value);
                nearCacheConfig.setLocalUpdatePolicy(policy);
            } else if ("eviction".equals(nodeName)) {
                nearCacheConfig.setEvictionConfig(getEvictionConfig(child));
            }
        }
        clientConfig.addNearCacheConfig(name, nearCacheConfig);
    }


    private EvictionConfig getEvictionConfig(final Node node) {
        final EvictionConfig evictionConfig = new EvictionConfig();
        final Node size = node.getAttributes().getNamedItem("size");
        final Node maxSizePolicy = node.getAttributes().getNamedItem("max-size-policy");
        final Node evictionPolicy = node.getAttributes().getNamedItem("eviction-policy");
        if (size != null) {
            evictionConfig.setSize(Integer.parseInt(getTextContent(size)));
        }
        if (maxSizePolicy != null) {
            evictionConfig.setMaximumSizePolicy(
                    EvictionConfig.MaxSizePolicy.valueOf(
                            upperCaseInternal(getTextContent(maxSizePolicy)))
            );
        }
        if (evictionPolicy != null) {
            evictionConfig.setEvictionPolicy(
                    EvictionPolicy.valueOf(
                            upperCaseInternal(getTextContent(evictionPolicy)))
            );
        }
        return evictionConfig;
    }

    private void handleLoadBalancer(Node node) {
        final String type = getAttribute(node, "type");
        if ("random".equals(type)) {
            clientConfig.setLoadBalancer(new RandomLB());
        } else if ("round-robin".equals(type)) {
            clientConfig.setLoadBalancer(new RoundRobinLB());
        }
    }

    private void handleNetwork(Node node) {
        final ClientNetworkConfig clientNetworkConfig = new ClientNetworkConfig();
        for (Node child : childElements(node)) {
            final String nodeName = cleanNodeName(child);
            if ("cluster-members".equals(nodeName)) {
                handleClusterMembers(child, clientNetworkConfig);
            } else if ("smart-routing".equals(nodeName)) {
                clientNetworkConfig.setSmartRouting(Boolean.parseBoolean(getTextContent(child)));
            } else if ("redo-operation".equals(nodeName)) {
                clientNetworkConfig.setRedoOperation(Boolean.parseBoolean(getTextContent(child)));
            } else if ("connection-timeout".equals(nodeName)) {
                clientNetworkConfig.setConnectionTimeout(Integer.parseInt(getTextContent(child)));
            } else if ("connection-attempt-period".equals(nodeName)) {
                clientNetworkConfig.setConnectionAttemptPeriod(Integer.parseInt(getTextContent(child)));
            } else if ("connection-attempt-limit".equals(nodeName)) {
                clientNetworkConfig.setConnectionAttemptLimit(Integer.parseInt(getTextContent(child)));
            } else if ("socket-options".equals(nodeName)) {
                handleSocketOptions(child, clientNetworkConfig);
            } else if ("socket-interceptor".equals(nodeName)) {
                handleSocketInterceptorConfig(child, clientNetworkConfig);
            } else if ("ssl".equals(nodeName)) {
                handleSSLConfig(child, clientNetworkConfig);
            } else if ("aws".equals(nodeName)) {
                handleAWS(child, clientNetworkConfig);
            } else if ("discovery-strategies".equals(nodeName)) {
                handleDiscoveryStrategies(child, clientNetworkConfig);
            }
        }
        clientConfig.setNetworkConfig(clientNetworkConfig);
    }

    private void handleDiscoveryStrategies(Node node, ClientNetworkConfig clientNetworkConfig) {
        final DiscoveryConfig discoveryConfig = clientNetworkConfig.getDiscoveryConfig();
        for (Node child : childElements(node)) {
            String name = cleanNodeName(child);
            if ("discovery-strategy".equals(name)) {
                handleDiscoveryStrategy(child, discoveryConfig);
            } else if ("node-filter".equals(name)) {
                handleDiscoveryNodeFilter(child, discoveryConfig);
            }
        }
    }

    private void handleDiscoveryNodeFilter(Node node, DiscoveryConfig discoveryConfig) {
        final NamedNodeMap atts = node.getAttributes();

        final Node att = atts.getNamedItem("class");
        if (att != null) {
            discoveryConfig.setNodeFilterClass(getTextContent(att).trim());
        }
    }

    private void handleDiscoveryStrategy(Node node, DiscoveryConfig discoveryConfig) {
        final NamedNodeMap atts = node.getAttributes();

        boolean enabled = false;
        String clazz = null;

        for (int a = 0; a < atts.getLength(); a++) {
            final Node att = atts.item(a);
            final String value = getTextContent(att).trim();
            if ("enabled".equalsIgnoreCase(att.getNodeName())) {
                enabled = getBooleanValue(value);
            } else if ("class".equals(att.getNodeName())) {
                clazz = value;
            }
        }

        if (!enabled || clazz == null) {
            return;
        }

        Map<String, Comparable> properties = new HashMap<String, Comparable>();
        for (Node child : childElements(node)) {
            String name = cleanNodeName(child);
            if ("properties".equals(name)) {
                fillProperties(child, properties);
            }
        }

        discoveryConfig.addDiscoveryStrategyConfig(new DiscoveryStrategyConfig(clazz, properties));
    }

    private void handleAWS(Node node, ClientNetworkConfig clientNetworkConfig) {
        final ClientAwsConfig clientAwsConfig = handleAwsAttributes(node);
        for (Node n : childElements(node)) {
            final String value = getTextContent(n).trim();
            if ("secret-key".equals(cleanNodeName(n))) {
                clientAwsConfig.setSecretKey(value);
            } else if ("access-key".equals(cleanNodeName(n))) {
                clientAwsConfig.setAccessKey(value);
            } else if ("region".equals(cleanNodeName(n))) {
                clientAwsConfig.setRegion(value);
            } else if ("host-header".equals(cleanNodeName(n))) {
                clientAwsConfig.setHostHeader(value);
            } else if ("security-group-name".equals(cleanNodeName(n))) {
                clientAwsConfig.setSecurityGroupName(value);
            } else if ("tag-key".equals(cleanNodeName(n))) {
                clientAwsConfig.setTagKey(value);
            } else if ("tag-value".equals(cleanNodeName(n))) {
                clientAwsConfig.setTagValue(value);
            } else if ("inside-aws".equals(cleanNodeName(n))) {
                clientAwsConfig.setInsideAws(getBooleanValue(value));
            } else if ("iam-role".equals(cleanNodeName(n))) {
                clientAwsConfig.setIamRole(value);
            }
        }
        if (!clientAwsConfig.isInsideAws() && clientAwsConfig.getIamRole() != null) {
            throw new InvalidConfigurationException("You cannot set IAM Role from outside EC2");
        }
        clientNetworkConfig.setAwsConfig(clientAwsConfig);
    }

    private ClientAwsConfig handleAwsAttributes(Node node) {
        final NamedNodeMap atts = node.getAttributes();
        final ClientAwsConfig clientAwsConfig = new ClientAwsConfig();
        for (int i = 0; i < atts.getLength(); i++) {
            final Node att = atts.item(i);
            final String value = getTextContent(att).trim();
            if ("enabled".equalsIgnoreCase(att.getNodeName())) {
                clientAwsConfig.setEnabled(getBooleanValue(value));
            } else if (att.getNodeName().equals("connection-timeout-seconds")) {
                int timeout = getIntegerValue("connection-timeout-seconds", value);
                clientAwsConfig.setConnectionTimeoutSeconds(timeout);
            }
        }
        return clientAwsConfig;
    }

    private void handleSSLConfig(final Node node, ClientNetworkConfig clientNetworkConfig) {
        SSLConfig sslConfig = new SSLConfig();
        final NamedNodeMap atts = node.getAttributes();
        final Node enabledNode = atts.getNamedItem("enabled");
        final boolean enabled = enabledNode != null && getBooleanValue(getTextContent(enabledNode).trim());
        sslConfig.setEnabled(enabled);

        for (Node n : childElements(node)) {
            final String nodeName = cleanNodeName(n);
            if ("factory-class-name".equals(nodeName)) {
                sslConfig.setFactoryClassName(getTextContent(n).trim());
            } else if ("properties".equals(nodeName)) {
                fillProperties(n, sslConfig.getProperties());
            }
        }
        clientNetworkConfig.setSSLConfig(sslConfig);
    }

    private void handleSocketOptions(Node node, ClientNetworkConfig clientNetworkConfig) {
        SocketOptions socketOptions = clientConfig.getSocketOptions();
        for (Node child : childElements(node)) {
            final String nodeName = cleanNodeName(child);
            if ("tcp-no-delay".equals(nodeName)) {
                socketOptions.setTcpNoDelay(Boolean.parseBoolean(getTextContent(child)));
            } else if ("keep-alive".equals(nodeName)) {
                socketOptions.setKeepAlive(Boolean.parseBoolean(getTextContent(child)));
            } else if ("reuse-address".equals(nodeName)) {
                socketOptions.setReuseAddress(Boolean.parseBoolean(getTextContent(child)));
            } else if ("linger-seconds".equals(nodeName)) {
                socketOptions.setLingerSeconds(Integer.parseInt(getTextContent(child)));
            } else if ("buffer-size".equals(nodeName)) {
                socketOptions.setBufferSize(Integer.parseInt(getTextContent(child)));
            }
        }
        clientNetworkConfig.setSocketOptions(socketOptions);
    }

    private void handleClusterMembers(Node node, ClientNetworkConfig clientNetworkConfig) {
        for (Node child : childElements(node)) {
            if ("address".equals(cleanNodeName(child))) {
                clientNetworkConfig.addAddress(getTextContent(child));
            }
        }
    }

    private void handleListeners(Node node) throws Exception {
        for (Node child : childElements(node)) {
            if ("listener".equals(cleanNodeName(child))) {
                String className = getTextContent(child);
                clientConfig.addListenerConfig(new ListenerConfig(className));
            }
        }
    }

    private void handleGroup(Node node) {
        for (Node n : childElements(node)) {
            final String value = getTextContent(n).trim();
            final String nodeName = cleanNodeName(n);
            if ("name".equals(nodeName)) {
                clientConfig.getGroupConfig().setName(value);
            } else if ("password".equals(nodeName)) {
                clientConfig.getGroupConfig().setPassword(value);
            }
        }
    }

    private void handleSerialization(Node node) {
        SerializationConfig serializationConfig = parseSerialization(node);
        clientConfig.setSerializationConfig(serializationConfig);
    }

    private void handleProxyFactories(Node node) throws Exception {
        for (Node child : childElements(node)) {
            final String nodeName = cleanNodeName(child);
            if ("proxy-factory".equals(nodeName)) {
                handleProxyFactory(child);
            }
        }
    }

    private void handleProxyFactory(Node node) throws Exception {
        final String service = getAttribute(node, "service");
        final String className = getAttribute(node, "class-name");

        final ProxyFactoryConfig proxyFactoryConfig = new ProxyFactoryConfig(className, service);
        clientConfig.addProxyFactoryConfig(proxyFactoryConfig);
    }

    private void handleSocketInterceptorConfig(final Node node, ClientNetworkConfig clientNetworkConfig) {
        SocketInterceptorConfig socketInterceptorConfig = parseSocketInterceptorConfig(node);
        clientNetworkConfig.setSocketInterceptorConfig(socketInterceptorConfig);
    }

    private void handleSecurity(Node node) throws Exception {
        ClientSecurityConfig clientSecurityConfig = new ClientSecurityConfig();
        for (Node child : childElements(node)) {
            final String nodeName = cleanNodeName(child);
            if ("credentials".equals(nodeName)) {
                String className = getTextContent(child);
                clientSecurityConfig.setCredentialsClassname(className);
            }
        }
        clientConfig.setSecurityConfig(clientSecurityConfig);
    }

}
