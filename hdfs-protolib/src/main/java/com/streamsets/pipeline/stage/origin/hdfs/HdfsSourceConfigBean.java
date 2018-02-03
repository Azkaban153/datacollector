/*
 * Copyright 2018 StreamSets Inc.
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
package com.streamsets.pipeline.stage.origin.hdfs;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigDefBean;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.config.DataFormat;

import com.streamsets.pipeline.stage.origin.lib.DataParserFormatConfig;
import com.streamsets.pipeline.lib.hdfs.common.Errors;
import com.streamsets.pipeline.lib.hdfs.common.HdfsBaseConfigBean;
import org.apache.hadoop.fs.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class HdfsSourceConfigBean extends HdfsBaseConfigBean {
  private static final Logger LOG = LoggerFactory.getLogger(HdfsSourceConfigBean.class);
  private static final int ONE = 1;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Input Directory",
      description = "Input Directory",
      group = "INPUT_FILES",
      displayPosition = 10
  )
  public String dirPath;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "File Pattern",
      group = "INPUT_FILES",
      displayPosition = 11
  )
  public String pattern;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "First File to Process",
      description = "the absolute path of the first file to process",
      group = "INPUT_FILES",
      displayPosition = 12
  )
  public String firstFile;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Number of Threads",
      defaultValue = "1",
      group = "INPUT_FILES",
      displayPosition = 1
  )
  public int numberOfThreads;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Batch Size (recs)",
      defaultValue = "1000",
      description = "Max number of records per batch",
      displayPosition = 20,
      group = "INPUT_FILES",
      min = 0,
      max = Integer.MAX_VALUE
  )
  public int maxBatchSize;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "60",
      label = "Batch Wait Time (secs)",
      description = "Max time to wait for new files before sending an empty batch",
      displayPosition = 30,
      group = "INPUT_FILES",
      min = 1
  )
  public long poolingTimeoutSecs;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Data Format",
      description = "Data Format",
      displayPosition = 1,
      group = "DATA_FORMAT"
  )
  @ValueChooserModel(DataFormatChooserValues.class)
  public DataFormat dataFormat;

  @ConfigDefBean(groups = "DATA_FORMAT")
  public DataParserFormatConfig dataFormatConfig = new DataParserFormatConfig();

  @Override
  protected String getConfigBeanPrefix() {
    return "hdfsSourceConfigBean.";
  }

  public void init(final Stage.Context context, List<Stage.ConfigIssue> issues) {
    boolean hadoopFSValidated = validateHadoopFS(context, issues);

    dataFormatConfig.stringBuilderPoolSize = numberOfThreads;
    dataFormatConfig.init(
        context,
        dataFormat,
        com.streamsets.pipeline.stage.destination.hdfs.Groups.OUTPUT_FILES.name(),
        getConfigBeanPrefix() + "dataGeneratorFormatConfig",
        issues
    );

    if (hadoopFSValidated){
      try {

      }  catch (Exception ex) {
        LOG.info("Validation Error: " + Errors.HADOOPFS_11.getMessage(), ex.toString(), ex);
        issues.add(context.createConfigIssue(com.streamsets.pipeline.stage.destination.hdfs.Groups.OUTPUT_FILES.name(), null, Errors.HADOOPFS_11, ex.toString(), ex));
      }
    }
  }

  protected FileSystem getFileSystem() {
    return fs;
  }
}
