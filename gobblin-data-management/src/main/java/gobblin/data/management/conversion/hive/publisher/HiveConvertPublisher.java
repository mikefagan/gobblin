/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.data.management.conversion.hive.publisher;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import java.util.Set;
import javax.annotation.Nonnull;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.State;
import gobblin.configuration.WorkUnitState;
import gobblin.configuration.WorkUnitState.WorkingState;
import gobblin.data.management.conversion.hive.avro.AvroSchemaManager;
import gobblin.data.management.conversion.hive.entities.QueryBasedHivePublishEntity;
import gobblin.data.management.conversion.hive.events.EventConstants;
import gobblin.data.management.conversion.hive.events.EventWorkunitUtils;
import gobblin.data.management.conversion.hive.query.HiveAvroORCQueryGenerator;
import gobblin.data.management.conversion.hive.source.HiveSource;
import gobblin.data.management.conversion.hive.source.HiveWorkUnit;
import gobblin.data.management.conversion.hive.watermarker.HiveSourceWatermarker;
import gobblin.data.management.conversion.hive.watermarker.HiveSourceWatermarkerFactory;
import gobblin.data.management.conversion.hive.watermarker.PartitionLevelWatermarker;
import gobblin.util.HiveJdbcConnector;
import gobblin.instrumented.Instrumented;
import gobblin.metrics.MetricContext;
import gobblin.metrics.event.EventSubmitter;
import gobblin.metrics.event.sla.SlaEventSubmitter;
import gobblin.publisher.DataPublisher;
import gobblin.util.HadoopUtils;
import gobblin.util.WriterUtils;
import gobblin.util.reflection.GobblinConstructorUtils;


/**
 * A simple {@link DataPublisher} updates the watermark and working state
 */
@Slf4j
public class HiveConvertPublisher extends DataPublisher {

  private final AvroSchemaManager avroSchemaManager;
  private final HiveJdbcConnector hiveJdbcConnector;
  private MetricContext metricContext;
  private EventSubmitter eventSubmitter;
  private final FileSystem fs;
  private final HiveSourceWatermarker watermarker;

  public HiveConvertPublisher(State state) throws IOException {
    super(state);
    this.avroSchemaManager = new AvroSchemaManager(FileSystem.get(HadoopUtils.newConfiguration()), state);
    this.metricContext = Instrumented.getMetricContext(state, HiveConvertPublisher.class);
    this.eventSubmitter = new EventSubmitter.Builder(this.metricContext, EventConstants.CONVERSION_NAMESPACE).build();

    Configuration conf = new Configuration();
    Optional<String> uri = Optional.fromNullable(this.state.getProp(ConfigurationKeys.WRITER_FILE_SYSTEM_URI));
    if (uri.isPresent()) {
      this.fs = FileSystem.get(URI.create(uri.get()), conf);
    } else {
      this.fs = FileSystem.get(conf);
    }

    try {
      this.hiveJdbcConnector = HiveJdbcConnector.newConnectorWithProps(state.getProperties());
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    this.watermarker =
        GobblinConstructorUtils.invokeConstructor(
            HiveSourceWatermarkerFactory.class, state.getProp(HiveSource.HIVE_SOURCE_WATERMARKER_FACTORY_CLASS_KEY,
                HiveSource.DEFAULT_HIVE_SOURCE_WATERMARKER_FACTORY_CLASS)).createFromState(state);
  }

  @Override
  public void initialize() throws IOException {
  }

  @Override
  public void publishData(Collection<? extends WorkUnitState> states) throws IOException {

    Set<String> cleanUpQueries = Sets.newLinkedHashSet();
    Set<String> publishQueries = Sets.newLinkedHashSet();
    List<String> directoriesToDelete = Lists.newArrayList();

    try {
      if (Iterables.tryFind(states, UNSUCCESSFUL_WORKUNIT).isPresent()) {
        /////////////////////////////////////////
        // Prepare cleanup and ignore publish
        /////////////////////////////////////////
        for (WorkUnitState wus : states) {
          QueryBasedHivePublishEntity publishEntity = HiveAvroORCQueryGenerator.deserializePublishCommands(wus);

          // Add cleanup commands - to be executed later
          if (publishEntity.getCleanupQueries() != null) {
            cleanUpQueries.addAll(publishEntity.getCleanupQueries());
          }

          if (publishEntity.getCleanupDirectories() != null) {
            directoriesToDelete.addAll(publishEntity.getCleanupDirectories());
          }

          EventWorkunitUtils.setBeginPublishDDLExecuteTimeMetadata(wus, System.currentTimeMillis());
          wus.setWorkingState(WorkingState.FAILED);
          if (!wus.getPropAsBoolean(PartitionLevelWatermarker.IS_WATERMARK_WORKUNIT_KEY)) {
            try {
              new SlaEventSubmitter(eventSubmitter, EventConstants.CONVERSION_FAILED_EVENT, wus.getProperties()).submit();
            } catch (Exception e) {
              log.error("Failed while emitting SLA event, but ignoring and moving forward to curate " + "all clean up comamnds", e);
            }
          }
        }
      } else {
        /////////////////////////////////////////
        // Prepare publish and cleanup commands
        /////////////////////////////////////////
        for (WorkUnitState wus : PARTITION_PUBLISH_ORDERING.sortedCopy(states)) {
          QueryBasedHivePublishEntity publishEntity = HiveAvroORCQueryGenerator.deserializePublishCommands(wus);

          // Add cleanup commands - to be executed later
          if (publishEntity.getCleanupQueries() != null) {
            cleanUpQueries.addAll(publishEntity.getCleanupQueries());
          }

          if (publishEntity.getCleanupDirectories() != null) {
            directoriesToDelete.addAll(publishEntity.getCleanupDirectories());
          }

          if (publishEntity.getPublishDirectories() != null) {
            // Publish snapshot / partition directories
            Map<String, String> publishDirectories = publishEntity.getPublishDirectories();
            for (Map.Entry<String, String> publishDir : publishDirectories.entrySet()) {
              moveDirectory(publishDir.getKey(), publishDir.getValue());
            }
          }

          if (publishEntity.getPublishQueries() != null) {
            publishQueries.addAll(publishEntity.getPublishQueries());
          }
        }

        /////////////////////////////////////////
        // Core publish
        /////////////////////////////////////////

        // Update publish start timestamp on all workunits
        for (WorkUnitState wus : PARTITION_PUBLISH_ORDERING.sortedCopy(states)) {
          if (HiveAvroORCQueryGenerator.deserializePublishCommands(wus).getPublishQueries() != null) {
            EventWorkunitUtils.setBeginPublishDDLExecuteTimeMetadata(wus, System.currentTimeMillis());
          }
        }

        // Actual publish: Register snapshot / partition
        executeQueries(Lists.newArrayList(publishQueries));

        // Update publish completion timestamp on all workunits
        for (WorkUnitState wus : PARTITION_PUBLISH_ORDERING.sortedCopy(states)) {
          if (HiveAvroORCQueryGenerator.deserializePublishCommands(wus).getPublishQueries() != null) {
            EventWorkunitUtils.setEndPublishDDLExecuteTimeMetadata(wus, System.currentTimeMillis());
          }

          wus.setWorkingState(WorkingState.COMMITTED);
          this.watermarker.setActualHighWatermark(wus);

          // Emit an SLA event for conversion successful
          if (!wus.getPropAsBoolean(PartitionLevelWatermarker.IS_WATERMARK_WORKUNIT_KEY)) {
            try {
              new SlaEventSubmitter(eventSubmitter, EventConstants.CONVERSION_SUCCESSFUL_SLA_EVENT, wus.getProperties())
                  .submit();
            } catch (Exception e) {
              log.error("Failed while emitting SLA event, but ignoring and moving forward to curate " + "all clean up commands", e);
            }
          }
        }
      }
    } finally {
      /////////////////////////////////////////
      // Post publish cleanup
      /////////////////////////////////////////

      // Execute cleanup commands
      try {
        executeQueries(Lists.newArrayList(cleanUpQueries));
      } catch (Exception e) {
        log.error("Failed to cleanup staging entities in Hive metastore.", e);
      }
      try {
        deleteDirectories(directoriesToDelete);
      } catch (Exception e) {
        log.error("Failed to cleanup staging directories.", e);
      }
    }
  }

  private void moveDirectory(String sourceDir, String targetDir) throws IOException {
    // If targetDir exists, delete it
    if (this.fs.exists(new Path(targetDir))) {
      deleteDirectory(targetDir);
    }

    // Create parent directories of targetDir
    WriterUtils.mkdirsWithRecursivePermission(this.fs, new Path(targetDir).getParent(),
        FsPermission.getCachePoolDefault());

    // Move directory
    log.info("Moving directory: " + sourceDir + " to: " + targetDir);
    if (!this.fs.rename(new Path(sourceDir), new Path(targetDir))) {
      throw new IOException(String.format("Unable to move %s to %s", sourceDir, targetDir));
    }
  }

  private void deleteDirectories(List<String> directoriesToDelete) throws IOException {
    for (String directory : directoriesToDelete) {
      deleteDirectory(directory);
    }
  }

  private void deleteDirectory(String dirToDelete) throws IOException {
    if (StringUtils.isBlank(dirToDelete)) {
      return;
    }

    log.info("Going to delete existing partition data: " + dirToDelete);
    this.fs.delete(new Path(dirToDelete), true);
  }

  private void executeQueries(List<String> queries) {
    if (null == queries || queries.size() == 0) {
      return;
    }
    try {
      this.hiveJdbcConnector.executeStatements(queries.toArray(new String[queries.size()]));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void publishMetadata(Collection<? extends WorkUnitState> states) throws IOException {
  }

  @Override
  public void close() throws IOException {
    this.avroSchemaManager.cleanupTempSchemas();
    this.hiveJdbcConnector.close();
  }

  private static final Predicate<WorkUnitState> UNSUCCESSFUL_WORKUNIT = new Predicate<WorkUnitState>() {

    @Override
    public boolean apply(WorkUnitState input) {
      return null == input || !WorkingState.SUCCESSFUL.equals(input.getWorkingState());
    }
  };

  /**
   * Publish workunits in lexicographic order of partition names.
   * If a workunit is a noop workunit then {@link HiveWorkUnit#getPartitionName()}
   * would be absent. This can happen while using {@link PartitionLevelWatermarker} where it creates a dummy workunit
   * for all watermarks. It is safe to always publish this dummy workunit at the end as we do not want to update the
   * ActualHighWatermark till all other partitions are successfully published. Hence we use nullsLast ordering.
   */
  private static final Ordering<WorkUnitState> PARTITION_PUBLISH_ORDERING = Ordering.natural().nullsLast()
      .onResultOf(new Function<WorkUnitState, String>() {
        public String apply(@Nonnull WorkUnitState wus) {
          return new HiveWorkUnit(wus.getWorkunit()).getPartitionName().orNull();
        }
      });
}
