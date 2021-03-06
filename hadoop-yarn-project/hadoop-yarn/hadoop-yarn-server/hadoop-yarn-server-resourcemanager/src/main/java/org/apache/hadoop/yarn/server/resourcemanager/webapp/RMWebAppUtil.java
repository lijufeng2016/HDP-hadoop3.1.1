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

package org.apache.hadoop.yarn.server.resourcemanager.webapp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.http.HttpServer2;
import org.apache.hadoop.http.lib.StaticUserWebFilter;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.AuthenticationFilterInitializer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.HttpCrossOriginFilterInitializer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.server.AuthenticationFilter;
import org.apache.hadoop.security.authentication.server.KerberosAuthenticationHandler;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LogAggregationContext;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.security.RMDelegationTokenSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.ApplicationSubmissionContextInfo;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.CredentialsInfo;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.LocalResourceInfo;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.LogAggregationContextInfo;
import org.apache.hadoop.yarn.server.security.http.RMAuthenticationFilter;
import org.apache.hadoop.yarn.server.security.http.RMAuthenticationFilterInitializer;
import org.apache.hadoop.yarn.webapp.BadRequestException;
import org.apache.hadoop.yarn.webapp.WebAppException;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;

import com.google.common.collect.ImmutableMap;

/**
 * Util class for ResourceManager WebApp.
 */
public final class RMWebAppUtil {

  private static final Log LOG = LogFactory.getLog(RMWebAppUtil.class);

  /**
   * Private constructor.
   */
  private RMWebAppUtil() {
    // not called
  }

  /**
   * Helper method to setup filters and authentication for ResourceManager
   * WebServices.
   *
   * Use the customized yarn filter instead of the standard kerberos filter to
   * allow users to authenticate using delegation tokens 4 conditions need to be
   * satisfied:
   *
   * 1. security is enabled.
   *
   * 2. http auth type is set to kerberos.
   *
   * 3. "yarn.resourcemanager.webapp.use-yarn-filter" override is set to true.
   *
   * 4. hadoop.http.filter.initializers container
   * AuthenticationFilterInitializer.
   *
   * @param conf RM configuration.
   * @param rmDTSecretManager RM specific delegation token secret manager.
   **/
  public static void setupSecurityAndFilters(Configuration conf,
      RMDelegationTokenSecretManager rmDTSecretManager) {

    boolean enableCorsFilter =
        conf.getBoolean(YarnConfiguration.RM_WEBAPP_ENABLE_CORS_FILTER,
            YarnConfiguration.DEFAULT_RM_WEBAPP_ENABLE_CORS_FILTER);
    boolean useYarnAuthenticationFilter = conf.getBoolean(
        YarnConfiguration.RM_WEBAPP_DELEGATION_TOKEN_AUTH_FILTER,
        YarnConfiguration.DEFAULT_RM_WEBAPP_DELEGATION_TOKEN_AUTH_FILTER);
    String authPrefix = "hadoop.http.authentication.";
    String authTypeKey = authPrefix + "type";
    String filterInitializerConfKey = "hadoop.http.filter.initializers";
    String actualInitializers = "";
    Class<?>[] initializersClasses = conf.getClasses(filterInitializerConfKey);

    // setup CORS
    if (enableCorsFilter) {
      conf.setBoolean(HttpCrossOriginFilterInitializer.PREFIX
          + HttpCrossOriginFilterInitializer.ENABLED_SUFFIX, true);
    }

    boolean hasHadoopAuthFilterInitializer = false;
    boolean hasRMAuthFilterInitializer = false;
    if (initializersClasses != null) {
      for (Class<?> initializer : initializersClasses) {
        if (initializer.getName()
            .equals(AuthenticationFilterInitializer.class.getName())) {
          hasHadoopAuthFilterInitializer = true;
        }
        if (initializer.getName()
            .equals(RMAuthenticationFilterInitializer.class.getName())) {
          hasRMAuthFilterInitializer = true;
        }
      }
      if (UserGroupInformation.isSecurityEnabled()
          && useYarnAuthenticationFilter && hasHadoopAuthFilterInitializer
          && conf.get(authTypeKey, "")
              .equals(KerberosAuthenticationHandler.TYPE)) {
        ArrayList<String> target = new ArrayList<String>();
        for (Class<?> filterInitializer : initializersClasses) {
          if (filterInitializer.getName()
              .equals(AuthenticationFilterInitializer.class.getName())) {
            if (!hasRMAuthFilterInitializer) {
              target.add(RMAuthenticationFilterInitializer.class.getName());
            }
            continue;
          }
          target.add(filterInitializer.getName());
        }
        actualInitializers = StringUtils.join(",", target);

        LOG.info("Using RM authentication filter(kerberos/delegation-token)"
            + " for RM webapp authentication");
        RMAuthenticationFilter
            .setDelegationTokenSecretManager(rmDTSecretManager);
        conf.set(filterInitializerConfKey, actualInitializers);
      }
    }

    // if security is not enabled and the default filter initializer has not
    // been set, set the initializer to include the
    // RMAuthenticationFilterInitializer which in turn will set up the simple
    // auth filter.

    String initializers = conf.get(filterInitializerConfKey);
    if (!UserGroupInformation.isSecurityEnabled()) {
      if (initializersClasses == null || initializersClasses.length == 0) {
        conf.set(filterInitializerConfKey,
            RMAuthenticationFilterInitializer.class.getName());
        conf.set(authTypeKey, "simple");
      } else if (initializers.equals(StaticUserWebFilter.class.getName())) {
        conf.set(filterInitializerConfKey,
            RMAuthenticationFilterInitializer.class.getName() + ","
                + initializers);
        conf.set(authTypeKey, "simple");
      }
    }
  }

  /**
   * Create the actual ApplicationSubmissionContext to be submitted to the RM
   * from the information provided by the user.
   *
   * @param newApp the information provided by the user
   * @param conf RM configuration
   * @return returns the constructed ApplicationSubmissionContext
   * @throws IOException in case of Error
   */
  public static ApplicationSubmissionContext createAppSubmissionContext(
      ApplicationSubmissionContextInfo newApp, Configuration conf)
      throws IOException {

    // create local resources and app submission context

    ApplicationId appid;
    String error =
        "Could not parse application id " + newApp.getApplicationId();
    try {
      appid = ApplicationId.fromString(newApp.getApplicationId());
    } catch (Exception e) {
      throw new BadRequestException(error);
    }
    ApplicationSubmissionContext appContext = ApplicationSubmissionContext
        .newInstance(appid, newApp.getApplicationName(), newApp.getQueue(),
            Priority.newInstance(newApp.getPriority()),
            createContainerLaunchContext(newApp), newApp.getUnmanagedAM(),
            newApp.getCancelTokensWhenComplete(), newApp.getMaxAppAttempts(),
            createAppSubmissionContextResource(newApp, conf),
            newApp.getApplicationType(),
            newApp.getKeepContainersAcrossApplicationAttempts(),
            newApp.getAppNodeLabelExpression(),
            newApp.getAMContainerNodeLabelExpression());
    appContext.setApplicationTags(newApp.getApplicationTags());
    appContext.setAttemptFailuresValidityInterval(
        newApp.getAttemptFailuresValidityInterval());
    if (newApp.getLogAggregationContextInfo() != null) {
      appContext.setLogAggregationContext(
          createLogAggregationContext(newApp.getLogAggregationContextInfo()));
    }
    String reservationIdStr = newApp.getReservationId();
    if (reservationIdStr != null && !reservationIdStr.isEmpty()) {
      ReservationId reservationId =
          ReservationId.parseReservationId(reservationIdStr);
      appContext.setReservationID(reservationId);
    }
    return appContext;
  }

  /**
   * Create the actual Resource inside the ApplicationSubmissionContextInfo to
   * be submitted to the RM from the information provided by the user.
   *
   * @param newApp the information provided by the user
   * @param conf RM configuration
   * @return returns the constructed Resource inside the
   *         ApplicationSubmissionContextInfo
   * @throws BadRequestException
   */
  private static Resource createAppSubmissionContextResource(
      ApplicationSubmissionContextInfo newApp, Configuration conf)
      throws BadRequestException {
    if (newApp.getResource().getvCores() > conf.getInt(
        YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES)) {
      String msg = "Requested more cores than configured max";
      throw new BadRequestException(msg);
    }
    if (newApp.getResource().getMemorySize() > conf.getInt(
        YarnConfiguration.RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB)) {
      String msg = "Requested more memory than configured max";
      throw new BadRequestException(msg);
    }
    Resource r = Resource.newInstance(newApp.getResource().getMemorySize(),
        newApp.getResource().getvCores());
    return r;
  }

  /**
   * Create the ContainerLaunchContext required for the
   * ApplicationSubmissionContext. This function takes the user information and
   * generates the ByteBuffer structures required by the ContainerLaunchContext
   *
   * @param newApp the information provided by the user
   * @return created context
   * @throws BadRequestException
   * @throws IOException
   */
  private static ContainerLaunchContext createContainerLaunchContext(
      ApplicationSubmissionContextInfo newApp)
      throws BadRequestException, IOException {

    // create container launch context

    HashMap<String, ByteBuffer> hmap = new HashMap<String, ByteBuffer>();
    for (Map.Entry<String, String> entry : newApp
        .getContainerLaunchContextInfo().getAuxillaryServiceData().entrySet()) {
      if (!entry.getValue().isEmpty()) {
        Base64 decoder = new Base64(0, null, true);
        byte[] data = decoder.decode(entry.getValue());
        hmap.put(entry.getKey(), ByteBuffer.wrap(data));
      }
    }

    HashMap<String, LocalResource> hlr = new HashMap<String, LocalResource>();
    for (Map.Entry<String, LocalResourceInfo> entry : newApp
        .getContainerLaunchContextInfo().getResources().entrySet()) {
      LocalResourceInfo l = entry.getValue();
      LocalResource lr = LocalResource.newInstance(URL.fromURI(l.getUrl()),
          l.getType(), l.getVisibility(), l.getSize(), l.getTimestamp());
      hlr.put(entry.getKey(), lr);
    }

    DataOutputBuffer out = new DataOutputBuffer();
    Credentials cs = createCredentials(
        newApp.getContainerLaunchContextInfo().getCredentials());
    cs.writeTokenStorageToStream(out);
    ByteBuffer tokens = ByteBuffer.wrap(out.getData());

    ContainerLaunchContext ctx = ContainerLaunchContext.newInstance(hlr,
        newApp.getContainerLaunchContextInfo().getEnvironment(),
        newApp.getContainerLaunchContextInfo().getCommands(), hmap, tokens,
        newApp.getContainerLaunchContextInfo().getAcls());

    return ctx;
  }

  /**
   * Generate a Credentials object from the information in the CredentialsInfo
   * object.
   *
   * @param credentials the CredentialsInfo provided by the user.
   * @return
   */
  private static Credentials createCredentials(CredentialsInfo credentials) {
    Credentials ret = new Credentials();
    try {
      for (Map.Entry<String, String> entry : credentials.getTokens()
          .entrySet()) {
        Text alias = new Text(entry.getKey());
        Token<TokenIdentifier> token = new Token<TokenIdentifier>();
        token.decodeFromUrlString(entry.getValue());
        ret.addToken(alias, token);
      }
      for (Map.Entry<String, String> entry : credentials.getSecrets()
          .entrySet()) {
        Text alias = new Text(entry.getKey());
        Base64 decoder = new Base64(0, null, true);
        byte[] secret = decoder.decode(entry.getValue());
        ret.addSecretKey(alias, secret);
      }
    } catch (IOException ie) {
      throw new BadRequestException(
          "Could not parse credentials data; exception message = "
              + ie.getMessage());
    }
    return ret;
  }

  private static LogAggregationContext createLogAggregationContext(
      LogAggregationContextInfo logAggregationContextInfo) {
    return LogAggregationContext.newInstance(
        logAggregationContextInfo.getIncludePattern(),
        logAggregationContextInfo.getExcludePattern(),
        logAggregationContextInfo.getRolledLogsIncludePattern(),
        logAggregationContextInfo.getRolledLogsExcludePattern(),
        logAggregationContextInfo.getLogAggregationPolicyClassName(),
        logAggregationContextInfo.getLogAggregationPolicyParameters());
  }

 /**
   * Helper method to retrieve the UserGroupInformation from the
   * HttpServletRequest.
   *
   * @param hsr the servlet request
   * @param usePrincipal true if we need to use the principal user, remote
   *          otherwise.
   * @return the user group information of the caller.
   **/
  public static UserGroupInformation getCallerUserGroupInformation(
      HttpServletRequest hsr, boolean usePrincipal) {

    String remoteUser = hsr.getRemoteUser();
    if (usePrincipal) {
      Principal princ = hsr.getUserPrincipal();
      remoteUser = princ == null ? null : princ.getName();
    }

    UserGroupInformation callerUGI = null;
    if (remoteUser != null) {
      callerUGI = UserGroupInformation.createRemoteUser(remoteUser);
    }

    return callerUGI;
  }

  /**
   * UI2 Context doesn't have all custom filters configured for RM. Hence add
   * all required filters to UI2 as well.
   *
   * Filters which are set as /* for pathSpec from Default Context:
   * 1. NoCacheFilter
   * 2. safety
   * 3. org.apache.hadoop.security.http.XFrameOptionsFilter
   * 4. AuthenticationFilter
   *      Default context has multiple pathSpecs such as /cluster, /jmx etc.
   *      These are not relevant for UI2 and hence only /* will be added as
   *      pathSpec for UI2.
   *
   * Filters which are to be set as NULL (pathSpec) similar to Default Context:
   * 1. SpnegoFilter
   *      In Spnego Filter, pathSpec is specified as NULL. This is not ensure
   *      that this filter should come after AuthenticationFilter in filter
   *      chain. Hence using same for UI2 context as well.
   *
   * Filters which are skipped for UI2:
   * 1. guice
   *
   * @param ui2Context New UI2 context.
   * @param httpServer Default httpServer from context.
   * @param conf Configuration Object.
   */
  public static void addFiltersForUI2Context(WebAppContext ui2Context,
      HttpServer2 httpServer, Configuration conf) {

    // Similar to Default context, DefaultServlet needs to be added to
    // set jetty related configs for redirect/welcomes files etc.
    ServletHolder holder = new ServletHolder(new DefaultServlet());
    Map<String, String> params = ImmutableMap.<String, String> builder()
        .put("acceptRanges", "true").put("dirAllowed", "false")
        .put("gzip", "true").put("useFileMappedBuffer", "true")
        .put("redirectWelcome", "true").build();
    holder.setInitParameters(params);
    ui2Context.setWelcomeFiles(new String[] {"index.html"});
    ui2Context.addServlet(holder, "/");
    ui2Context.setDisplayName("ui2");
    String tempDirectory = conf.get(HttpServer2.HTTP_TEMP_DIR_KEY);
    if (tempDirectory != null && !tempDirectory.isEmpty()) {
      ui2Context.setTempDirectory(new File(tempDirectory));
      ui2Context.setAttribute("javax.servlet.context.tempdir", tempDirectory);
    }

    ui2Context.getServletContext()
        .setAttribute(HttpServer2.CONF_CONTEXT_ATTRIBUTE, conf);
    ui2Context.getServletContext().setAttribute(HttpServer2.ADMINS_ACL,
        new AccessControlList(conf.get(YarnConfiguration.YARN_ADMIN_ACL,
            YarnConfiguration.DEFAULT_YARN_ADMIN_ACL)));

    // Get filterHolders and filterMappings from default context's servlet.
    FilterHolder[] filterHolders = httpServer.getWebAppContext()
        .getServletHandler().getFilters();
    FilterMapping[] filterMappings = httpServer.getWebAppContext()
        .getServletHandler().getFilterMappings();

    // To define a filter for UI2 context, URL path also needed which is
    // available only in FilterMapping. Hence convert this to a map for easy
    // access.
    Set<String> nonNullFilterMappings = new HashSet<>();
    for (FilterMapping filterMapping : filterMappings) {
      if (filterMapping.getPathSpecs() != null) {
        nonNullFilterMappings.add(filterMapping.getFilterName());
      }
    }

    // Loop through all filterHolders and add one by one to UI2.
    LOG.info("Add filters from default webapp context to UI2 context.");
    for (FilterHolder filterHolder : filterHolders) {
      // Skip guice filter for UI2.
      if ("guice".equals(filterHolder.getName())) {
        continue;
      }

      List<String> pathSpecs = new ArrayList<>();

      // Given a filter from default context has some specific pathSpec,
      // UI2 can apply the same filter at /* on the root context path.
      if (nonNullFilterMappings.contains(filterHolder.getName())) {
        pathSpecs.add("/*");
      }

      // Define filter to UI2 context with corrected pathSpec.
      HttpServer2.defineFilter(ui2Context, filterHolder.getName(),
          filterHolder.getClassName(), filterHolder.getInitParameters(),
          pathSpecs.toArray(new String[pathSpecs.size()]));

      LOG.info("UI2 context filter Name:" + filterHolder.getName()
          + ", className=" + filterHolder.getClassName());
    }
  }
}
