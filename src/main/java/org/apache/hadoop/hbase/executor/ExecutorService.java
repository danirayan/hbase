/**
 * Copyright 2010 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.executor;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * This is a generic executor service. This component abstracts a
 * threadpool, a queue to which jobs can be submitted and a Runnable that
 * handles the object that is added to the queue.
 *
 * <p>In order to create a new service, create an instance of this class and 
 * then do: <code>instance.startExecutorService("myService");</code>.  When done
 * call {@link #shutdown()}.
 *
 * In order to use the service created above, you need to override the
 * <code>EventHandler</code> class and create an event type that submits to this
 * service.
 */
public class ExecutorService {
  private static final Log LOG = LogFactory.getLog(ExecutorService.class);

  // hold the all the executors created in a map addressable by their names
  private final ConcurrentHashMap<String, Executor> executorMap =
    new ConcurrentHashMap<String, Executor>();

  private final String servername;

  /**
   * The following is a list of names for the various executor services in both
   * the master and the region server.
   */
  public enum ExecutorType {

    // Master executor services
    MASTER_CLOSE_REGION        (1),
    MASTER_OPEN_REGION         (2),
    MASTER_SERVER_OPERATIONS   (3),
    MASTER_TABLE_OPERATIONS    (4),
    MASTER_RS_SHUTDOWN         (5),

    // RegionServer executor services
    RS_OPEN_REGION             (20),
    RS_OPEN_ROOT               (21),
    RS_OPEN_META               (22),
    RS_CLOSE_REGION            (23),
    RS_CLOSE_ROOT              (24),
    RS_CLOSE_META              (25);

    ExecutorType(int value) {}

    String getExecutorName(String serverName) {
      return this.toString() + "-" + serverName;
    }
  }

  /**
   * Default constructor.
   * @param Name of the hosting server.
   */
  public ExecutorService(final String servername) {
    super();
    this.servername = servername;
  }

  /**
   * Start an executor service with a given name. If there was a service already
   * started with the same name, this throws a RuntimeException.
   * @param name Name of the service to start.
   */
  void startExecutorService(String name, int maxThreads) {
    if (this.executorMap.get(name) != null) {
      throw new RuntimeException("An executor service with the name " + name +
        " is already running!");
    }
    Executor hbes = new Executor(name, maxThreads);
    if (this.executorMap.putIfAbsent(name, hbes) != null) {
      throw new RuntimeException("An executor service with the name " + name +
      " is already running (2)!");
    }
    LOG.debug("Starting executor service: " + name);
  }

  boolean isExecutorServiceRunning(String name) {
    return this.executorMap.containsKey(name);
  }

  public void shutdown() {
    for(Entry<String, Executor> entry: this.executorMap.entrySet()) {
      List<Runnable> wasRunning =
        entry.getValue().threadPoolExecutor.shutdownNow();
      if (!wasRunning.isEmpty()) {
        LOG.info(entry.getKey() + " had " + wasRunning + " on shutdown");
      }
    }
    this.executorMap.clear();
  }

  Executor getExecutor(final ExecutorType type) {
    return getExecutor(type.getExecutorName(this.servername));
  }

  Executor getExecutor(String name) {
    Executor executor = this.executorMap.get(name);
    if (executor == null) {
      LOG.debug("Executor service [" + name + "] not found in " + this.executorMap);
    }
    return executor;
  }


  public void startExecutorService(final ExecutorType type, final int maxThreads) {
    String name = type.getExecutorName(this.servername);
    if (isExecutorServiceRunning(name)) {
      LOG.debug("Executor service " + toString() + " already running on " +
        this.servername);
      return;
    }
    startExecutorService(name, maxThreads);
  }

  public void submit(final EventHandler eh) {
    getExecutor(eh.getExecutorType()).submit(eh);
  }

  /**
   * Executor instance.
   */
  private static class Executor {
    // default number of threads in the pool
    private int corePoolSize = 1;
    // how long to retain excess threads
    private long keepAliveTimeInMillis = 1000;
    // the thread pool executor that services the requests
    private final ThreadPoolExecutor threadPoolExecutor;
    // work queue to use - unbounded queue
    BlockingQueue<Runnable> workQueue = new PriorityBlockingQueue<Runnable>();
    private final AtomicInteger threadid = new AtomicInteger(0);
    private final String name;

    protected Executor(String name, int maxThreads) {
      this.name = name;
      // create the thread pool executor
      this.threadPoolExecutor = new ThreadPoolExecutor(corePoolSize, maxThreads,
          keepAliveTimeInMillis, TimeUnit.MILLISECONDS, workQueue);
      // name the threads for this threadpool
      ThreadFactoryBuilder tfb = new ThreadFactoryBuilder();
      tfb.setNameFormat(this.name + "-" + this.threadid.incrementAndGet());
      this.threadPoolExecutor.setThreadFactory(tfb.build());
    }

    /**
     * Submit the event to the queue for handling.
     * @param event
     */
    void submit(Runnable event) {
      this.threadPoolExecutor.execute(event);
    }
  }
}