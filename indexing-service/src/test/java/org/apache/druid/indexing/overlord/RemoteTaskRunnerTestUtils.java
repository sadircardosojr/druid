/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.overlord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingCluster;
import org.apache.druid.common.guava.DSuppliers;
import org.apache.druid.curator.PotentiallyGzippedCompressionProvider;
import org.apache.druid.curator.cache.PathChildrenCacheFactory;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.IndexingServiceCondition;
import org.apache.druid.indexing.common.TestUtils;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.overlord.autoscaling.NoopProvisioningStrategy;
import org.apache.druid.indexing.overlord.autoscaling.ProvisioningStrategy;
import org.apache.druid.indexing.overlord.config.RemoteTaskRunnerConfig;
import org.apache.druid.indexing.overlord.setup.DefaultWorkerBehaviorConfig;
import org.apache.druid.indexing.overlord.setup.WorkerBehaviorConfig;
import org.apache.druid.indexing.worker.TaskAnnouncement;
import org.apache.druid.indexing.worker.Worker;
import org.apache.druid.indexing.worker.config.WorkerConfig;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.server.initialization.IndexerZkConfig;
import org.apache.druid.server.initialization.ZkPathsConfig;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.zookeeper.CreateMode;

import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class RemoteTaskRunnerTestUtils
{
  static final Joiner JOINER = Joiner.on("/");
  static final String BASE_PATH = "/test/druid";
  static final String ANNOUNCEMENTS_PATH = StringUtils.format("%s/indexer/announcements", BASE_PATH);
  static final String TASKS_PATH = StringUtils.format("%s/indexer/tasks", BASE_PATH);
  static final String STATUS_PATH = StringUtils.format("%s/indexer/status", BASE_PATH);
  static final TaskLocation DUMMY_LOCATION = TaskLocation.create("dummy", 9000, -1);

  private TestingCluster testingCluster;

  private CuratorFramework cf;
  private ObjectMapper jsonMapper;

  RemoteTaskRunnerTestUtils()
  {
    TestUtils testUtils = new TestUtils();
    jsonMapper = testUtils.getTestObjectMapper();
  }

  CuratorFramework getCuratorFramework()
  {
    return cf;
  }

  ObjectMapper getObjectMapper()
  {
    return jsonMapper;
  }

  void setUp() throws Exception
  {
    testingCluster = new TestingCluster(1);
    testingCluster.start();

    cf = CuratorFrameworkFactory.builder()
                                .connectString(testingCluster.getConnectString())
                                .retryPolicy(new ExponentialBackoffRetry(1, 10))
                                .compressionProvider(new PotentiallyGzippedCompressionProvider(false))
                                .build();
    cf.start();
    cf.blockUntilConnected();
    cf.create().creatingParentsIfNeeded().forPath(BASE_PATH);
    cf.create().creatingParentsIfNeeded().forPath(TASKS_PATH);
  }

  void tearDown() throws Exception
  {
    cf.close();
    testingCluster.stop();
  }

  RemoteTaskRunner makeRemoteTaskRunner(RemoteTaskRunnerConfig config)
  {
    NoopProvisioningStrategy<WorkerTaskRunner> resourceManagement = new NoopProvisioningStrategy<>();
    return makeRemoteTaskRunner(config, resourceManagement);
  }

  public RemoteTaskRunner makeRemoteTaskRunner(
      RemoteTaskRunnerConfig config,
      ProvisioningStrategy<WorkerTaskRunner> provisioningStrategy
  )
  {
    RemoteTaskRunner remoteTaskRunner = new TestableRemoteTaskRunner(
        jsonMapper,
        config,
        new IndexerZkConfig(
            new ZkPathsConfig()
            {
              @Override
              public String getBase()
              {
                return BASE_PATH;
              }
            }, null, null, null, null
        ),
        cf,
        new PathChildrenCacheFactory.Builder(),
        null,
        DSuppliers.of(new AtomicReference<>(DefaultWorkerBehaviorConfig.defaultConfig())),
        provisioningStrategy
    );

    remoteTaskRunner.start();
    return remoteTaskRunner;
  }

  Worker makeWorker(final String workerId, final int capacity) throws Exception
  {
    Worker worker = new Worker(
        "http",
        workerId,
        workerId,
        capacity,
        "0",
        WorkerConfig.DEFAULT_CATEGORY
    );

    cf.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(
        JOINER.join(ANNOUNCEMENTS_PATH, workerId),
        jsonMapper.writeValueAsBytes(worker)
    );
    cf.create().creatingParentsIfNeeded().forPath(JOINER.join(TASKS_PATH, workerId));

    return worker;
  }

  void disableWorker(Worker worker) throws Exception
  {
    cf.setData().forPath(
        JOINER.join(ANNOUNCEMENTS_PATH, worker.getHost()),
        jsonMapper.writeValueAsBytes(new Worker(
            worker.getScheme(),
            worker.getHost(),
            worker.getIp(),
            worker.getCapacity(),
            "",
            worker.getCategory()
        ))
    );
  }

  void mockWorkerRunningTask(final String workerId, final Task task) throws Exception
  {
    cf.delete().forPath(JOINER.join(TASKS_PATH, workerId, task.getId()));

    final String taskStatusPath = JOINER.join(STATUS_PATH, workerId, task.getId());
    TaskAnnouncement taskAnnouncement = TaskAnnouncement.create(task, TaskStatus.running(task.getId()), DUMMY_LOCATION);
    cf.create()
      .creatingParentsIfNeeded()
      .forPath(taskStatusPath, jsonMapper.writeValueAsBytes(taskAnnouncement));

    Preconditions.checkNotNull(
        cf.checkExists().forPath(taskStatusPath),
        "Failed to write status on [%s]",
        taskStatusPath
    );
  }

  void mockWorkerCompleteSuccessfulTask(final String workerId, final Task task) throws Exception
  {
    TaskAnnouncement taskAnnouncement = TaskAnnouncement.create(task, TaskStatus.success(task.getId()), DUMMY_LOCATION);
    cf.setData().forPath(JOINER.join(STATUS_PATH, workerId, task.getId()), jsonMapper.writeValueAsBytes(taskAnnouncement));
  }

  void mockWorkerCompleteFailedTask(final String workerId, final Task task) throws Exception
  {
    TaskAnnouncement taskAnnouncement = TaskAnnouncement.create(
        task,
        TaskStatus.failure(
            task.getId(),
            "Dummy task status failure for testing"
        ),
        DUMMY_LOCATION
    );
    cf.setData()
      .forPath(JOINER.join(STATUS_PATH, workerId, task.getId()), jsonMapper.writeValueAsBytes(taskAnnouncement));
  }

  boolean workerRunningTask(final String workerId, final String taskId)
  {
    return pathExists(JOINER.join(STATUS_PATH, workerId, taskId));
  }

  boolean taskAnnounced(final String workerId, final String taskId)
  {
    return pathExists(JOINER.join(TASKS_PATH, workerId, taskId));
  }

  boolean pathExists(final String path)
  {
    return TestUtils.conditionValid(
        new IndexingServiceCondition()
        {
          @Override
          public boolean isValid()
          {
            try {
              return cf.checkExists().forPath(path) != null;
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public String toString()
          {
            return StringUtils.format("Path[%s] exists", path);
          }
        }
    );
  }

  public static class TestableRemoteTaskRunner extends RemoteTaskRunner
  {
    private long currentTimeMillis = System.currentTimeMillis();

    public TestableRemoteTaskRunner(
        ObjectMapper jsonMapper,
        RemoteTaskRunnerConfig config,
        IndexerZkConfig indexerZkConfig,
        CuratorFramework cf,
        PathChildrenCacheFactory.Builder pathChildrenCacheFactory,
        HttpClient httpClient,
        Supplier<WorkerBehaviorConfig> workerConfigRef,
        ProvisioningStrategy<WorkerTaskRunner> provisioningStrategy
    )
    {
      super(
          jsonMapper,
          config,
          indexerZkConfig,
          cf,
          pathChildrenCacheFactory,
          httpClient,
          workerConfigRef,
          provisioningStrategy,
          new NoopServiceEmitter()
      );
    }

    void setCurrentTimeMillis(long currentTimeMillis)
    {
      this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    protected long getCurrentTimeMillis()
    {
      return currentTimeMillis;
    }
  }
}
