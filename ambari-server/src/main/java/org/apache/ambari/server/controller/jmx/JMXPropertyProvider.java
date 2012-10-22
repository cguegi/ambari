/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.controller.jmx;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.utilities.StreamProvider;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.PropertyId;
import org.apache.ambari.server.controller.spi.PropertyProvider;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Property provider implementation for JMX sources.
 */
public class JMXPropertyProvider implements PropertyProvider {

  protected static final PropertyId HOST_COMPONENT_HOST_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("host_name", "HostRoles");
  protected static final PropertyId HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("component_name", "HostRoles");

  private static final String CATEGORY_KEY = "tag.context";
//  private static final String NAME_KEY     = "name";

  /**
   * Set of property ids supported by this provider.
   */
  private final Set<PropertyId> propertyIds;

  private final StreamProvider streamProvider;

  private final Map<String, String> hostMapping;

  private static final Map<String, String> JMX_PORTS = new HashMap<String, String>();

  static {
    JMX_PORTS.put("NAMENODE", "50070");
    JMX_PORTS.put("HBASE_MASTER", "60010");
    JMX_PORTS.put("JOBTRACKER", "50030");
    JMX_PORTS.put("DATANODE", "50075");
    JMX_PORTS.put("TASKTRACKER", "50060");
  }


  // ----- Constructors ------------------------------------------------------

  /**
   * Create a JMX property provider.
   *
   * @param propertyIds     the property ids provided by this provider
   * @param streamProvider  the stream provider
   * @param hostMapping     the host mapping
   */
  public JMXPropertyProvider(Set<PropertyId> propertyIds,
                              StreamProvider streamProvider,
                              Map<String, String> hostMapping) {
    this.propertyIds    = propertyIds;
    this.streamProvider = streamProvider;
    this.hostMapping    = hostMapping;
  }


  // ----- PropertyProvider --------------------------------------------------

  @Override
  public Set<Resource> populateResources(Set<Resource> resources,
                                         Request request,
                                         Predicate predicate)
      throws AmbariException {
    Set<Resource> keepers = new HashSet<Resource>();
    for (Resource resource : resources) {
      if (populateResource(resource, request, predicate)) {
        keepers.add(resource);
      }
    }
    return keepers;
  }

  @Override
  public Set<PropertyId> getPropertyIds() {
    return propertyIds;
  }


  // ----- helper methods ----------------------------------------------------

  /**
   * Populate a resource by obtaining the requested JMX properties.
   *
   * @param resource  the resource to be populated
   * @param request   the request
   * @param predicate the predicate
   *
   * @return true if the resource was successfully populated with the requested properties
   *
   * @throws AmbariException thrown if the resource cannot be populated
   */
  private boolean populateResource(Resource resource,
                                   Request request,
                                   Predicate predicate)
      throws AmbariException {

    if (getPropertyIds().isEmpty()) {
      return true;
    }

    Set<PropertyId> ids = PropertyHelper.getRequestPropertyIds(getPropertyIds(), request, predicate);

    String hostName = hostMapping.get( resource.getPropertyValue(HOST_COMPONENT_HOST_NAME_PROPERTY_ID));
    String port     = JMX_PORTS.get(resource.getPropertyValue(HOST_COMPONENT_COMPONENT_NAME_PROPERTY_ID));

    if (hostName == null || port == null) {
      return true;
    }

    String spec = getSpec(hostName + ":" + port);

    try {
      JMXMetricHolder metricHolder = new ObjectMapper().readValue(streamProvider.readFrom(spec), JMXMetricHolder.class);

      Map<String, Map<String, Object>> categories = new HashMap<String, Map<String, Object>>();

      for (Map<String, Object> bean : metricHolder.getBeans()) {
        String category = getCategory(bean);
        if (category != null) {
          categories.put(category, bean);
        }
      }

      for (PropertyId propertyId : ids) {
        String category = propertyId.getCategory();

        // strip off 'metrics/' from the category
        if (category.startsWith("metrics/")) {
          category = category.substring(8);
        }

        Map<String, Object> properties = categories.get(category);
        String name = propertyId.getName();
        if (properties != null && properties.containsKey(name)) {
          resource.setProperty(propertyId, properties.get(name));
        }
      }
    } catch (IOException e) {
      throw new AmbariException("Can't get metrics : " + spec, e);
    }

    return true;
  }

  private String getCategory(Map<String, Object> bean) {
    if (bean.containsKey(CATEGORY_KEY)) {
      return (String) bean.get(CATEGORY_KEY);
    }
//    if (bean.containsKey(NAME_KEY)) {
//      try {
//        ObjectName objectName = new ObjectName((String) bean.get(NAME_KEY));
//
//
//      } catch (MalformedObjectNameException e) {
//        // TODO : log this
//      }
//    }
    return null;
  }

  /**
   * Get the spec to locate the JMX stream from the given source
   *
   * @param jmxSource  the source (host and port)
   *
   * @return the spec
   */
  protected String getSpec(String jmxSource) {
    return "http://" + jmxSource + "/jmx?qry=Hadoop:*";
  }
}
