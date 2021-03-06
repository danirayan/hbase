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
package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.RegionException;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.UnknownRegionException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.ipc.HMasterInterface;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.ipc.RemoteException;

/**
 * Provides an interface to manage HBase database table metadata + general 
 * administrative functions.  Use HBaseAdmin to create, drop, list, enable and 
 * disable tables. Use it also to add and drop table column families. 
 * 
 * See {@link HTable} to add, update, and delete data from an individual table.
 */
public class HBaseAdmin implements Abortable {
  private final Log LOG = LogFactory.getLog(this.getClass().getName());
//  private final HConnection connection;
  final HConnection connection;
  private volatile Configuration conf;
  private final long pause;
  private final int numRetries;
  /**
   * Lazily instantiated.  Use {@link #getCatalogTracker()} to ensure you get
   * an instance rather than a null.
   */
  private CatalogTracker catalogTracker = null;

  /**
   * Constructor
   *
   * @param conf Configuration object
   * @throws MasterNotRunningException if the master is not running
   * @throws ZooKeeperConnectionException if unable to connect to zookeeper
   */
  public HBaseAdmin(Configuration conf)
  throws MasterNotRunningException, ZooKeeperConnectionException {
    this.connection = HConnectionManager.getConnection(conf);
    this.conf = conf;
    this.pause = conf.getLong("hbase.client.pause", 30 * 1000);
    this.numRetries = conf.getInt("hbase.client.retries.number", 5);
    this.connection.getMaster();
  }

  private synchronized CatalogTracker getCatalogTracker()
  throws ZooKeeperConnectionException, IOException {
    if (this.catalogTracker == null) {
      this.catalogTracker = new CatalogTracker(this.connection.getZooKeeperWatcher(),
        ServerConnectionManager.getConnection(conf), this,
        this.conf.getInt("hbase.admin.catalog.timeout", 10 * 1000));
      try {
        this.catalogTracker.start();
      } catch (InterruptedException e) {
        // Let it out as an IOE for now until we redo all so tolerate IEs
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted", e);
      }
    }
    return this.catalogTracker;
  }

  @Override
  public void abort(String why, Throwable e) {
    // Currently does nothing but throw the passed message and exception
    throw new RuntimeException(why, e);
  }

  /** @return HConnection used by this object. */
  public HConnection getConnection() {
    return connection;
  }

  /**
   * Get a connection to the currently set master.
   * @return proxy connection to master server for this instance
   * @throws MasterNotRunningException if the master is not running
   * @throws ZooKeeperConnectionException if unable to connect to zookeeper
   */
  public HMasterInterface getMaster()
  throws MasterNotRunningException, ZooKeeperConnectionException {
    return this.connection.getMaster();
  }

  /** @return - true if the master server is running
   * @throws ZooKeeperConnectionException
   * @throws MasterNotRunningException */
  public boolean isMasterRunning()
  throws MasterNotRunningException, ZooKeeperConnectionException {
    return this.connection.isMasterRunning();
  }

  /**
   * @param tableName Table to check.
   * @return True if table exists already.
   * @throws IOException 
   */
  public boolean tableExists(final String tableName)
  throws IOException {
    return MetaReader.tableExists(getCatalogTracker(), tableName);
  }

  /**
   * @param tableName Table to check.
   * @return True if table exists already.
   * @throws IOException 
   */
  public boolean tableExists(final byte [] tableName)
  throws IOException {
    return tableExists(Bytes.toString(tableName));
  }

  /**
   * List all the userspace tables.  In other words, scan the META table.
   *
   * If we wanted this to be really fast, we could implement a special
   * catalog table that just contains table names and their descriptors.
   * Right now, it only exists as part of the META table's region info.
   *
   * @return - returns an array of HTableDescriptors
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor[] listTables() throws IOException {
    return this.connection.listTables();
  }


  /**
   * Method for getting the tableDescriptor
   * @param tableName as a byte []
   * @return the tableDescriptor
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor getTableDescriptor(final byte [] tableName)
  throws IOException {
    return this.connection.getHTableDescriptor(tableName);
  }

  private long getPauseTime(int tries) {
    int triesCount = tries;
    if (triesCount >= HConstants.RETRY_BACKOFF.length) {
      triesCount = HConstants.RETRY_BACKOFF.length - 1;
    }
    return this.pause * HConstants.RETRY_BACKOFF[triesCount];
  }

  /**
   * Creates a new table.
   * Synchronous operation.
   *
   * @param desc table descriptor for table
   *
   * @throws IllegalArgumentException if the table name is reserved
   * @throws MasterNotRunningException if master is not running
   * @throws TableExistsException if table already exists (If concurrent
   * threads, the table may have been created between test-for-existence
   * and attempt-at-creation).
   * @throws IOException if a remote or network exception occurs
   */
  public void createTable(HTableDescriptor desc)
  throws IOException {
    createTable(desc, null);
  }

  /**
   * Creates a new table with the specified number of regions.  The start key
   * specified will become the end key of the first region of the table, and
   * the end key specified will become the start key of the last region of the
   * table (the first region has a null start key and the last region has a
   * null end key).
   *
   * BigInteger math will be used to divide the key range specified into
   * enough segments to make the required number of total regions.
   *
   * Synchronous operation.
   *
   * @param desc table descriptor for table
   * @param startKey beginning of key range
   * @param endKey end of key range
   * @param numRegions the total number of regions to create
   *
   * @throws IllegalArgumentException if the table name is reserved
   * @throws MasterNotRunningException if master is not running
   * @throws TableExistsException if table already exists (If concurrent
   * threads, the table may have been created between test-for-existence
   * and attempt-at-creation).
   * @throws IOException
   */
  public void createTable(HTableDescriptor desc, byte [] startKey,
      byte [] endKey, int numRegions)
  throws IOException {
    HTableDescriptor.isLegalTableName(desc.getName());
    if(numRegions < 3) {
      throw new IllegalArgumentException("Must create at least three regions");
    } else if(Bytes.compareTo(startKey, endKey) >= 0) {
      throw new IllegalArgumentException("Start key must be smaller than end key");
    }
    byte [][] splitKeys = Bytes.split(startKey, endKey, numRegions - 3);
    if(splitKeys == null || splitKeys.length != numRegions - 1) {
      throw new IllegalArgumentException("Unable to split key range into enough regions");
    }
    createTable(desc, splitKeys);
  }

  /**
   * Creates a new table with an initial set of empty regions defined by the
   * specified split keys.  The total number of regions created will be the
   * number of split keys plus one (the first region has a null start key and
   * the last region has a null end key).
   * Synchronous operation.
   *
   * @param desc table descriptor for table
   * @param splitKeys array of split keys for the initial regions of the table
   *
   * @throws IllegalArgumentException if the table name is reserved
   * @throws MasterNotRunningException if master is not running
   * @throws TableExistsException if table already exists (If concurrent
   * threads, the table may have been created between test-for-existence
   * and attempt-at-creation).
   * @throws IOException
   */
  public void createTable(HTableDescriptor desc, byte [][] splitKeys)
  throws IOException {
    HTableDescriptor.isLegalTableName(desc.getName());
    if(splitKeys != null && splitKeys.length > 1) {
      Arrays.sort(splitKeys, Bytes.BYTES_COMPARATOR);
      // Verify there are no duplicate split keys
      byte [] lastKey = null;
      for(byte [] splitKey : splitKeys) {
        if(lastKey != null && Bytes.equals(splitKey, lastKey)) {
          throw new IllegalArgumentException("All split keys must be unique, found duplicate");
        }
        lastKey = splitKey;
      }
    }
    createTableAsync(desc, splitKeys);
    for (int tries = 0; tries < numRetries; tries++) {
      try {
        // Wait for new table to come on-line
        connection.locateRegion(desc.getName(), HConstants.EMPTY_START_ROW);
        break;

      } catch (RegionException e) {
        if (tries == numRetries - 1) {
          // Ran out of tries
          throw e;
        }
      }
      try {
        Thread.sleep(getPauseTime(tries));
      } catch (InterruptedException e) {
        // Just continue; ignore the interruption.
      }
    }
  }

  /**
   * Creates a new table but does not block and wait for it to come online.
   * Asynchronous operation.
   *
   * @param desc table descriptor for table
   *
   * @throws IllegalArgumentException Bad table name.
   * @throws MasterNotRunningException if master is not running
   * @throws TableExistsException if table already exists (If concurrent
   * threads, the table may have been created between test-for-existence
   * and attempt-at-creation).
   * @throws IOException
   */
  public void createTableAsync(HTableDescriptor desc, byte [][] splitKeys)
  throws IOException {
    HTableDescriptor.isLegalTableName(desc.getName());
    try {
      getMaster().createTable(desc, splitKeys);
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
  }

  /**
   * Deletes a table.
   * Synchronous operation.
   *
   * @param tableName name of table to delete
   * @throws IOException if a remote or network exception occurs
   */
  public void deleteTable(final String tableName) throws IOException {
    deleteTable(Bytes.toBytes(tableName));
  }

  /**
   * Deletes a table.
   * Synchronous operation.
   *
   * @param tableName name of table to delete
   * @throws IOException if a remote or network exception occurs
   */
  public void deleteTable(final byte [] tableName) throws IOException {
    isMasterRunning();
    HTableDescriptor.isLegalTableName(tableName);
    HRegionLocation firstMetaServer = getFirstMetaServerForTable(tableName);
    try {
      getMaster().deleteTable(tableName);
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
    final int batchCount = this.conf.getInt("hbase.admin.scanner.caching", 10);
    // Wait until first region is deleted
    HRegionInterface server =
      connection.getHRegionConnection(firstMetaServer.getServerAddress());
    HRegionInfo info = new HRegionInfo();
    for (int tries = 0; tries < numRetries; tries++) {
      long scannerId = -1L;
      try {
        Scan scan = new Scan().addColumn(HConstants.CATALOG_FAMILY,
          HConstants.REGIONINFO_QUALIFIER);
        scannerId = server.openScanner(
          firstMetaServer.getRegionInfo().getRegionName(), scan);
        // Get a batch at a time.
        Result [] values = server.next(scannerId, batchCount);
        if (values == null || values.length == 0) {
          break;
        }
        boolean found = false;
        for (Result r : values) {
          NavigableMap<byte[], byte[]> infoValues =
              r.getFamilyMap(HConstants.CATALOG_FAMILY);
          for (Map.Entry<byte[], byte[]> e : infoValues.entrySet()) {
            if (Bytes.equals(e.getKey(), HConstants.REGIONINFO_QUALIFIER)) {
              info = (HRegionInfo) Writables.getWritable(e.getValue(), info);
              if (Bytes.equals(info.getTableDesc().getName(), tableName)) {
                found = true;
              } else {
                found = false;
                break;
              }
            }
          }
        }
        if (!found) {
          break;
        }
      } catch (IOException ex) {
        if(tries == numRetries - 1) {           // no more tries left
          if (ex instanceof RemoteException) {
            ex = RemoteExceptionHandler.decodeRemoteException((RemoteException) ex);
          }
          throw ex;
        }
      } finally {
        if (scannerId != -1L) {
          try {
            server.close(scannerId);
          } catch (Exception ex) {
            LOG.warn(ex);
          }
        }
      }
      try {
        Thread.sleep(getPauseTime(tries));
      } catch (InterruptedException e) {
        // continue
      }
    }
    // Delete cached information to prevent clients from using old locations
    HConnectionManager.deleteConnection(conf, false);
    LOG.info("Deleted " + Bytes.toString(tableName));
  }



  /**
   * Brings a table on-line (enables it).
   * Synchronous operation.
   *
   * @param tableName name of the table
   * @throws IOException if a remote or network exception occurs
   */
  public void enableTable(final String tableName) throws IOException {
    enableTable(Bytes.toBytes(tableName));
  }

  /**
   * Brings a table on-line (enables it).
   * Synchronous operation.
   *
   * @param tableName name of the table
   * @throws IOException if a remote or network exception occurs
   */
  public void enableTable(final byte [] tableName) throws IOException {
    isMasterRunning();

    // Wait until all regions are enabled
    boolean enabled = false;
    for (int tries = 0; tries < this.numRetries; tries++) {
      try {
        getMaster().enableTable(tableName);
      } catch (RemoteException e) {
        throw RemoteExceptionHandler.decodeRemoteException(e);
      }
      enabled = isTableEnabled(tableName);
      if (enabled) {
        break;
      }
      long sleep = getPauseTime(tries);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Sleeping= " + sleep + "ms, waiting for all regions to be " +
          "enabled in " + Bytes.toString(tableName));
      }
      try {
        Thread.sleep(sleep);
      } catch (InterruptedException e) {
        // continue
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Wake. Waiting for all regions to be enabled from " +
          Bytes.toString(tableName));
      }
    }
    if (!enabled) {
      throw new IOException("Unable to enable table " +
        Bytes.toString(tableName));
    }
    LOG.info("Enabled table " + Bytes.toString(tableName));
  }

  /**
   * Disables a table (takes it off-line) If it is being served, the master
   * will tell the servers to stop serving it.
   * Synchronous operation.
   *
   * @param tableName name of table
   * @throws IOException if a remote or network exception occurs
   */
  public void disableTable(final String tableName) throws IOException {
    disableTable(Bytes.toBytes(tableName));
  }

  /**
   * Disables a table (takes it off-line) If it is being served, the master
   * will tell the servers to stop serving it.
   * Synchronous operation.
   *
   * @param tableName name of table
   * @throws IOException if a remote or network exception occurs
   */
  public void disableTable(final byte [] tableName) throws IOException {
    isMasterRunning();

    // Wait until all regions are disabled
    boolean disabled = false;
    for (int tries = 0; tries < this.numRetries; tries++) {
      try {
        getMaster().disableTable(tableName);
      } catch (RemoteException e) {
        throw RemoteExceptionHandler.decodeRemoteException(e);
      }
      disabled = isTableDisabled(tableName);
      if (disabled) {
        break;
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Sleep. Waiting for all regions to be disabled from " +
          Bytes.toString(tableName));
      }
      try {
        Thread.sleep(getPauseTime(tries));
      } catch (InterruptedException e) {
        // continue
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Wake. Waiting for all regions to be disabled from " +
          Bytes.toString(tableName));
      }
    }
    if (!disabled) {
      throw new RegionException("Retries exhausted, it took too long to wait"+
        " for the table " + Bytes.toString(tableName) + " to be disabled.");
    }
    LOG.info("Disabled " + Bytes.toString(tableName));
  }

  /**
   * @param tableName name of table to check
   * @return true if table is on-line
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableEnabled(String tableName) throws IOException {
    return isTableEnabled(Bytes.toBytes(tableName));
  }
  /**
   * @param tableName name of table to check
   * @return true if table is on-line
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableEnabled(byte[] tableName) throws IOException {
    return connection.isTableEnabled(tableName);
  }

  /**
   * @param tableName name of table to check
   * @return true if table is off-line
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableDisabled(byte[] tableName) throws IOException {
    return connection.isTableDisabled(tableName);
  }

  /**
   * @param tableName name of table to check
   * @return true if all regions of the table are available
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableAvailable(byte[] tableName) throws IOException {
    return connection.isTableAvailable(tableName);
  }

  /**
   * @param tableName name of table to check
   * @return true if all regions of the table are available
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableAvailable(String tableName) throws IOException {
    return connection.isTableAvailable(Bytes.toBytes(tableName));
  }

  /**
   * Add a column to an existing table.
   * Asynchronous operation.
   *
   * @param tableName name of the table to add column to
   * @param column column descriptor of column to be added
   * @throws IOException if a remote or network exception occurs
   */
  public void addColumn(final String tableName, HColumnDescriptor column)
  throws IOException {
    addColumn(Bytes.toBytes(tableName), column);
  }

  /**
   * Add a column to an existing table.
   * Asynchronous operation.
   *
   * @param tableName name of the table to add column to
   * @param column column descriptor of column to be added
   * @throws IOException if a remote or network exception occurs
   */
  public void addColumn(final byte [] tableName, HColumnDescriptor column)
  throws IOException {
    HTableDescriptor.isLegalTableName(tableName);
    try {
      getMaster().addColumn(tableName, column);
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
  }

  /**
   * Delete a column from a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param columnName name of column to be deleted
   * @throws IOException if a remote or network exception occurs
   */
  public void deleteColumn(final String tableName, final String columnName)
  throws IOException {
    deleteColumn(Bytes.toBytes(tableName), Bytes.toBytes(columnName));
  }

  /**
   * Delete a column from a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param columnName name of column to be deleted
   * @throws IOException if a remote or network exception occurs
   */
  public void deleteColumn(final byte [] tableName, final byte [] columnName)
  throws IOException {
    try {
      getMaster().deleteColumn(tableName, columnName);
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
  }

  /**
   * Modify an existing column family on a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param columnName name of column to be modified
   * @param descriptor new column descriptor to use
   * @throws IOException if a remote or network exception occurs
   * @deprecated The <code>columnName</code> is redundant. Use {@link #addColumn(String, HColumnDescriptor)}
   */
  public void modifyColumn(final String tableName, final String columnName,
      HColumnDescriptor descriptor)
  throws IOException {
    modifyColumn(tableName,  descriptor);
  }

  /**
   * Modify an existing column family on a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param descriptor new column descriptor to use
   * @throws IOException if a remote or network exception occurs
   */
  public void modifyColumn(final String tableName, HColumnDescriptor descriptor)
  throws IOException {
    modifyColumn(Bytes.toBytes(tableName), descriptor);
  }

  /**
   * Modify an existing column family on a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param columnName name of column to be modified
   * @param descriptor new column descriptor to use
   * @throws IOException if a remote or network exception occurs
   * @deprecated The <code>columnName</code> is redundant. Use {@link #modifyColumn(byte[], HColumnDescriptor)}
   */
  public void modifyColumn(final byte [] tableName, final byte [] columnName,
    HColumnDescriptor descriptor)
  throws IOException {
    modifyColumn(tableName, descriptor);
  }

  /**
   * Modify an existing column family on a table.
   * Asynchronous operation.
   *
   * @param tableName name of table
   * @param descriptor new column descriptor to use
   * @throws IOException if a remote or network exception occurs
   */
  public void modifyColumn(final byte [] tableName, HColumnDescriptor descriptor)
  throws IOException {
    try {
      getMaster().modifyColumn(tableName, descriptor);
    } catch (RemoteException re) {
      // Convert RE exceptions in here; client shouldn't have to deal with them,
      // at least w/ the type of exceptions that come out of this method:
      // TableNotFoundException, etc.
      throw RemoteExceptionHandler.decodeRemoteException(re);
    }
  }

  /**
   * Close a region. For expert-admins.
   * @param regionname region name to close
   * @param hostAndPort If supplied, we'll use this location rather than
   * the one currently in <code>.META.</code>
   * @throws IOException if a remote or network exception occurs
   */
  public void closeRegion(final String regionname, final String hostAndPort)
  throws IOException {
    closeRegion(Bytes.toBytes(regionname), hostAndPort);
  }

  /**
   * Close a region.  For expert-admins.
   * @param regionname region name to close
   * @param hostAndPort If supplied, we'll use this location rather than
   * the one currently in <code>.META.</code>
   * @throws IOException if a remote or network exception occurs
   */
  public void closeRegion(final byte [] regionname, final String hostAndPort)
  throws IOException {
    if (hostAndPort != null) {
      HServerAddress hsa = new HServerAddress(hostAndPort);
      Pair<HRegionInfo, HServerAddress> pair =
        MetaReader.getRegion(getCatalogTracker(), regionname);
      closeRegion(hsa, pair.getFirst());
    } else {
      Pair<HRegionInfo, HServerAddress> pair =
        MetaReader.getRegion(getCatalogTracker(), regionname);
      closeRegion(pair.getSecond(), pair.getFirst());
    }
  }

  private void closeRegion(final HServerAddress hsa, final HRegionInfo hri)
  throws IOException {
    HRegionInterface rs = this.connection.getHRegionConnection(hsa);
    rs.closeRegion(hri);
  }

  /**
   * Flush a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to flush
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void flush(final String tableNameOrRegionName)
  throws IOException, InterruptedException {
    flush(Bytes.toBytes(tableNameOrRegionName));
  }

  /**
   * Flush a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to flush
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void flush(final byte [] tableNameOrRegionName)
  throws IOException, InterruptedException {
    boolean isRegionName = isRegionName(tableNameOrRegionName);
    if (isRegionName) {
      Pair<HRegionInfo, HServerAddress> pair =
        MetaReader.getRegion(getCatalogTracker(), tableNameOrRegionName);
      flush(pair.getSecond(), pair.getFirst());
    } else {
      List<Pair<HRegionInfo, HServerAddress>> pairs =
        MetaReader.getTableRegionsAndLocations(getCatalogTracker(),
          Bytes.toString(tableNameOrRegionName));
      for (Pair<HRegionInfo, HServerAddress> pair: pairs) {
        flush(pair.getSecond(), pair.getFirst());
      }
    }
  }

  private void flush(final HServerAddress hsa, final HRegionInfo hri)
  throws IOException {
    HRegionInterface rs = this.connection.getHRegionConnection(hsa);
    rs.flushRegion(hri);
  }

  /**
   * Compact a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to compact
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void compact(final String tableNameOrRegionName)
  throws IOException, InterruptedException {
    compact(Bytes.toBytes(tableNameOrRegionName));
  }

  /**
   * Compact a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to compact
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void compact(final byte [] tableNameOrRegionName)
  throws IOException, InterruptedException {
    compact(tableNameOrRegionName, false);
  }

  /**
   * Major compact a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to major compact
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void majorCompact(final String tableNameOrRegionName)
  throws IOException, InterruptedException {
    majorCompact(Bytes.toBytes(tableNameOrRegionName));
  }

  /**
   * Major compact a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to major compact
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void majorCompact(final byte [] tableNameOrRegionName)
  throws IOException, InterruptedException {
    compact(tableNameOrRegionName, true);
  }

  /**
   * Compact a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to compact
   * @param major True if we are to do a major compaction.
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  private void compact(final byte [] tableNameOrRegionName, final boolean major)
  throws IOException, InterruptedException {
    if (isRegionName(tableNameOrRegionName)) {
      Pair<HRegionInfo, HServerAddress> pair =
        MetaReader.getRegion(getCatalogTracker(), tableNameOrRegionName);
      compact(pair.getSecond(), pair.getFirst(), major);
    } else {
      List<Pair<HRegionInfo, HServerAddress>> pairs =
        MetaReader.getTableRegionsAndLocations(getCatalogTracker(),
          Bytes.toString(tableNameOrRegionName));
      for (Pair<HRegionInfo, HServerAddress> pair: pairs) {
        compact(pair.getSecond(), pair.getFirst(), major);
      }
    }
  }

  private void compact(final HServerAddress hsa, final HRegionInfo hri,
      final boolean major)
  throws IOException {
    HRegionInterface rs = this.connection.getHRegionConnection(hsa);
    rs.compactRegion(hri, major);
  }

  /**
   * Move the region <code>r</code> to <code>dest</code>.
   * @param encodedRegionName The encoded region name.
   * @param destServerName The servername of the destination regionserver
   * @throws UnknownRegionException Thrown if we can't find a region named
   * <code>encodedRegionName</code>
   * @throws ZooKeeperConnectionException 
   * @throws MasterNotRunningException 
   */
  public void move(final byte [] encodedRegionName, final byte [] destServerName)
  throws UnknownRegionException, MasterNotRunningException, ZooKeeperConnectionException {
    getMaster().move(encodedRegionName, destServerName);
  }

  /**
   * @param b If true, enable balancer. If false, disable balancer.
   * @return Previous balancer value
   * @throws ZooKeeperConnectionException 
   * @throws MasterNotRunningException 
   */
  public boolean balance(final boolean b)
  throws MasterNotRunningException, ZooKeeperConnectionException {
    return getMaster().balance(b);
  }

  /**
   * Split a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table or region to split
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void split(final String tableNameOrRegionName)
  throws IOException, InterruptedException {
    split(Bytes.toBytes(tableNameOrRegionName));
  }

  /**
   * Split a table or an individual region.
   * Asynchronous operation.
   *
   * @param tableNameOrRegionName table to region to split
   * @throws IOException if a remote or network exception occurs
   * @throws InterruptedException 
   */
  public void split(final byte [] tableNameOrRegionName) throws IOException, InterruptedException {
    if (isRegionName(tableNameOrRegionName)) {
      // Its a possible region name.
      Pair<HRegionInfo, HServerAddress> pair =
        MetaReader.getRegion(getCatalogTracker(), tableNameOrRegionName);
      split(pair.getSecond(), pair.getFirst());
    } else {
      List<Pair<HRegionInfo, HServerAddress>> pairs =
        MetaReader.getTableRegionsAndLocations(getCatalogTracker(),
          Bytes.toString(tableNameOrRegionName));
      for (Pair<HRegionInfo, HServerAddress> pair: pairs) {
        split(pair.getSecond(), pair.getFirst());
      }
    }
  }

  private void split(final HServerAddress hsa, final HRegionInfo hri)
  throws IOException {
    HRegionInterface rs = this.connection.getHRegionConnection(hsa);
    rs.splitRegion(hri);
  }

  /**
   * Modify an existing table, more IRB friendly version.
   * Asynchronous operation.  This means that it may be a while before your
   * schema change is updated across all of the table.
   *
   * @param tableName name of table.
   * @param htd modified description of the table
   * @throws IOException if a remote or network exception occurs
   */
  public void modifyTable(final byte [] tableName, HTableDescriptor htd)
  throws IOException {
    try {
      getMaster().modifyTable(tableName, htd);
    } catch (RemoteException re) {
      // Convert RE exceptions in here; client shouldn't have to deal with them,
      // at least w/ the type of exceptions that come out of this method:
      // TableNotFoundException, etc.
      throw RemoteExceptionHandler.decodeRemoteException(re);
    }
  }

  /**
   * @param tableNameOrRegionName Name of a table or name of a region.
   * @return True if <code>tableNameOrRegionName</code> is *possibly* a region
   * name else false if a verified tablename (we call {@link #tableExists(byte[])};
   * else we throw an exception.
   * @throws IOException 
   */
  private boolean isRegionName(final byte [] tableNameOrRegionName)
  throws IOException {
    if (tableNameOrRegionName == null) {
      throw new IllegalArgumentException("Pass a table name or region name");
    }
    return !tableExists(tableNameOrRegionName);
  }

  /**
   * Shuts down the HBase cluster
   * @throws IOException if a remote or network exception occurs
   */
  public synchronized void shutdown() throws IOException {
    isMasterRunning();
    try {
      getMaster().shutdown();
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
  }

  /**
   * Shuts down the current HBase master only.
   * Does not shutdown the cluster.
   * @see #shutdown()
   * @throws IOException if a remote or network exception occurs
   */
  public synchronized void stopMaster() throws IOException {
    isMasterRunning();
    try {
      getMaster().stopMaster();
    } catch (RemoteException e) {
      throw RemoteExceptionHandler.decodeRemoteException(e);
    }
  }

  /**
   * @return cluster status
   * @throws IOException if a remote or network exception occurs
   */
  public ClusterStatus getClusterStatus() throws IOException {
    return getMaster().getClusterStatus();
  }

  private HRegionLocation getFirstMetaServerForTable(final byte [] tableName)
  throws IOException {
    return connection.locateRegion(HConstants.META_TABLE_NAME,
      HRegionInfo.createRegionName(tableName, null, HConstants.NINES, false));
  }

  /**
   * Check to see if HBase is running. Throw an exception if not.
   *
   * @param conf system configuration
   * @throws MasterNotRunningException if the master is not running
   * @throws ZooKeeperConnectionException if unable to connect to zookeeper
   */
  public static void checkHBaseAvailable(Configuration conf)
  throws MasterNotRunningException, ZooKeeperConnectionException {
    Configuration copyOfConf = HBaseConfiguration.create(conf);
    copyOfConf.setInt("hbase.client.retries.number", 1);
    new HBaseAdmin(copyOfConf);
  }
}