/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.knox.gateway.cm.descriptor;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.knox.gateway.ClouderaManagerIntegrationMessages;
import org.apache.knox.gateway.config.GatewayConfig;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;
import org.apache.knox.gateway.util.JsonUtils;

/**
 * Monitoring KNOX_DESCRIPTOR_DIR for *.cm files - which is a Hadoop XML configuration - and processing those files if they were modified
 * since the last time it they were processed
 */
public class ClouderaManagerDescriptorMonitor {

  private static final String CM_DESCRIPTOR_FILE_EXTENSION = ".cm";
  private static final ClouderaManagerIntegrationMessages LOG = MessagesFactory.get(ClouderaManagerIntegrationMessages.class);
  private final String descriptorsDir;
  private final long monitoringInterval;
  private final ScheduledExecutorService executorService;
  private FileTime lastReloadTime;

  public ClouderaManagerDescriptorMonitor(GatewayConfig gatewayConfig) {
    this.descriptorsDir = gatewayConfig.getGatewayDescriptorsDir();
    this.monitoringInterval = gatewayConfig.getClouderaManagerDescriptorsMonitoringInterval();
    this.executorService = Executors.newSingleThreadScheduledExecutor(new BasicThreadFactory.Builder().namingPattern("ClouderaManagerDescriptorMonitor-%d").build());
  }

  public void setupMonitor() {
    if (monitoringInterval > 0) {
      executorService.scheduleAtFixedRate(() -> monitorClouderaManagerDescriptors(), 0, monitoringInterval, TimeUnit.MILLISECONDS);
      LOG.monitoringClouderaManagerDescriptor(descriptorsDir);
    }
  }

  private void monitorClouderaManagerDescriptors() {
    final File[] clouderaManagerDescriptorFiles = new File(descriptorsDir).listFiles((FileFilter) new SuffixFileFilter(CM_DESCRIPTOR_FILE_EXTENSION));
    for (File clouderaManagerDescriptorFile : clouderaManagerDescriptorFiles) {
      monitorClouderaManagerDescriptor(Paths.get(clouderaManagerDescriptorFile.getAbsolutePath()));
    }
  }

  private void monitorClouderaManagerDescriptor(Path clouderaManagerDescriptorFile) {
    try {
      if (Files.isReadable(clouderaManagerDescriptorFile)) {
        final FileTime lastModifiedTime = Files.getLastModifiedTime(clouderaManagerDescriptorFile);
        if (lastReloadTime == null || lastReloadTime.compareTo(lastModifiedTime) < 0) {
          lastReloadTime = lastModifiedTime;
          processClouderaManagerDescriptor(clouderaManagerDescriptorFile.toString());
        }
      } else {
        LOG.failedToMonitorClouderaManagerDescriptor(clouderaManagerDescriptorFile.toString(), "File is not readable!", null);
      }
    } catch (IOException e) {
      LOG.failedToMonitorClouderaManagerDescriptor(clouderaManagerDescriptorFile.toString(), e.getMessage(), e);
    }
  }

  private void processClouderaManagerDescriptor(String descriptorFilePath) {
    ClouderaManagerDescriptorParser.parse(descriptorFilePath).forEach(simpleDescriptor -> {
      try {
        final File knoxDescriptorFile = new File(descriptorsDir, simpleDescriptor.getName() + ".json");
        FileUtils.writeStringToFile(knoxDescriptorFile, JsonUtils.renderAsJsonString(simpleDescriptor), StandardCharsets.UTF_8);
      } catch (IOException e) {
        LOG.failedToProduceKnoxDescriptor(e.getMessage(), e);
      }
    });
  }
}