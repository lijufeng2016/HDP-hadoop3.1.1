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

package org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.com.nec;

import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.Device;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DevicePlugin;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DevicePluginScheduler;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DeviceRegisterRequest;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.DeviceRuntimeSpec;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.YarnRuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

public class NECVEPlugin implements DevicePlugin, DevicePluginScheduler {

  public static final Logger LOG = LoggerFactory.getLogger(NECVEPlugin.class);

  private static final String[] DEFAULT_BINARY_SEARCH_DIRS = new String[]{
      "/usr/bin", "/bin", "/opt/nec/ve/bin"};

  private String binaryName = "nec-ve-get.py";
  private String binaryPath;
  private File binaryFile;

  public NECVEPlugin() throws Exception {
    String envBinaryName = System.getenv("NEC_VE_GET_SCRIPT_NAME");
    if (null != envBinaryName) {
      LOG.info("Use " + binaryName + "as script name.");
      this.binaryName = envBinaryName;
    }
    String envBinaryPath = System.getenv("NEC_VE_GET_SCRIPT_PATH");
    if (null != envBinaryPath) {
      this.binaryPath = envBinaryPath;
      LOG.info("Use script: " + binaryPath);
      return;
    }
    LOG.info("Search script..");

    boolean found = false;
    String yarnSbin = System.getenv("HADOOP_COMMON_HOME");
    if (null != yarnSbin) {
      String script = yarnSbin + "/sbin/DevicePluginScript/" + binaryName;
      if (new File(script).exists()) {
        this.binaryPath = script;
        LOG.info("Use script: " + binaryPath);
        return;
      }
    }
    // search binary exists
    for (String dir : DEFAULT_BINARY_SEARCH_DIRS) {
      binaryFile = new File(dir, binaryName);
      if (binaryFile.exists()) {
        found = true;
        this.binaryPath = binaryFile.getAbsolutePath();
        LOG.info("Found script:" + this.binaryPath);
        break;
      }
    }
    if (!found) {
      LOG.error("No binary found in below path"
          + DEFAULT_BINARY_SEARCH_DIRS.toString());
      throw new Exception("No binary found for " + NECVEPlugin.class);
    }

  }

  public DeviceRegisterRequest getRegisterRequestInfo() {
    return DeviceRegisterRequest.Builder.newInstance()
        .setResourceName("nec.com/ve").build();
  }

  public Set<Device> getDevices() {
    TreeSet<Device> r = new TreeSet<Device>();

    String output = null;
    Shell.ShellCommandExecutor shexec = new Shell.ShellCommandExecutor(
        new String[]{this.binaryPath});
    try {
      shexec.execute();
      output = shexec.getOutput();
      parseOutput(r,output);
    } catch (IOException e) {
      LOG.warn(e.toString());
    }
    return r;
  }

  public DeviceRuntimeSpec onDevicesAllocated(Set<Device> set,
      YarnRuntimeType yarnRuntimeType) throws Exception {
    return null;
  }

  /**
   * Sample one line in output:
   * id=0, dev=/dev/ve0, state=ONLINE, busId=0000:65:00.0, major=243, minor=0
   */
  private void parseOutput(TreeSet<Device> r, String output) {
    LOG.info("Parsing output: {}", output);
    String[] lines = output.split("\n");
    for (String line : lines) {
      Device device = null;
      Device.Builder builder = Device.Builder.newInstance();
      String[] keyvalues = line.trim().split(",");
      for (String keyvalue : keyvalues) {
        String[] tokens = keyvalue.trim().split("=");
        if (tokens.length != 2) {
          LOG.error("Unknown format of script output! Skip this line");
          break;
        }

        if (tokens[0].equals("id")) {
          builder.setId(Integer.valueOf(tokens[1]));
        }
        if (tokens[0].equals("dev")) {
          builder.setDevPath(tokens[1]);
        }
        if (tokens[0].equals("state")) {
          if(tokens[1].equals("ONLINE")) {
            builder.setHealthy(true);
          }
          builder.setStatus(tokens[1]);
        }
        if (tokens[0].equals("busId")) {
          builder.setBusID(tokens[1]);
        }
        if (tokens[0].equals("major")) {
          builder.setMajorNumber(Integer.valueOf(tokens[1]));
        }
        if (tokens[0].equals("minor")) {
          builder.setMinorNumber(Integer.valueOf(tokens[1]));
        }
      }// for key value pars
      device = builder.build();
      if (device.isHealthy()) {
        r.add(device);
      }
    }
  }

  public void onDevicesReleased(Set<Device> releasedDevices) {

  }

  public Set<Device> allocateDevices(Set<Device> availableDevices,
      int count) {
    // Can consider topology, utilization.etc
    Set<Device> allocated = new TreeSet<Device>();
    int number = 0;
    for (Device d : availableDevices) {
      allocated.add(d);
      number++;
      if (number == count) {
        break;
      }
    }
    return allocated;
  }
}
