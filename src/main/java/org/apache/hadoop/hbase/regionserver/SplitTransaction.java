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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.io.Reference.Range;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.hadoop.util.Progressable;
import org.apache.zookeeper.KeeperException;

/**
 * Executes region split as a "transaction".  Call {@link #prepare()} to setup
 * the transaction, {@link #execute(OnlineRegions)} to run the transaction and
 * {@link #rollback(OnlineRegions)} to cleanup if execute fails.
 *
 * <p>Here is an example of how you would use this class:
 * <pre>
 *  SplitTransaction st = new SplitTransaction(this.conf, parent, midKey)
 *  if (!st.prepare()) return;
 *  try {
 *    st.execute(myOnlineRegions);
 *  } catch (IOException ioe) {
 *    try {
 *      st.rollback(myOnlineRegions);
 *      return;
 *    } catch (RuntimeException e) {
 *      myAbortable.abort("Failed split, abort");
 *    }
 *  }
 * </Pre>
 */
class SplitTransaction {
  private static final Log LOG = LogFactory.getLog(SplitTransaction.class);
  private static final String SPLITDIR = "splits";

  /*
   * Region to split
   */
  private final HRegion parent;
  private HRegionInfo hri_a;
  private HRegionInfo hri_b;
  private Path splitdir;

  /*
   * Row to split around
   */
  private final byte [] splitrow;

  /**
   * Types to add to the transaction journal
   */
  enum JournalEntry {
    /**
     * We created the temporary split data directory.
     */
    CREATE_SPLIT_DIR,
    /**
     * Closed the parent region.
     */
    CLOSED_PARENT_REGION,
    /**
     * The parent has been taken out of the server's online regions list.
     */
    OFFLINED_PARENT,
    /**
     * Started in on creation of the first daughter region.
     */
    STARTED_REGION_A_CREATION,
    /**
     * Started in on the creation of the second daughter region.
     */
    STARTED_REGION_B_CREATION
  }

  /*
   * Journal of how far the split transaction has progressed.
   */
  private final List<JournalEntry> journal = new ArrayList<JournalEntry>();

  /**
   * Constructor
   * @param services So we can online new servces.  If null, we'll skip onlining
   * (Useful testing).
   * @param c Configuration to use running split
   * @param r Region to split
   * @param splitrow Row to split around
   */
  SplitTransaction(final HRegion r, final byte [] splitrow) {
    this.parent = r;
    this.splitrow = splitrow;
    this.splitdir = getSplitDir(this.parent);
  }

  /**
   * Does checks on split inputs.
   * @return <code>true</code> if the region is splittable else
   * <code>false</code> if it is not (e.g. its already closed, etc.). If we
   * return <code>true</code>, we'll have taken out the parent's
   * <code>splitsAndClosesLock</code> and only way to unlock is successful
   * {@link #execute(OnlineRegions)} or {@link #rollback(OnlineRegions)}
   */
  public boolean prepare() {
    boolean prepared = false;
    this.parent.lock.writeLock().lock();
    try {
      if (this.parent.isClosed() || this.parent.isClosing()) return prepared;
      HRegionInfo hri = this.parent.getRegionInfo();
      // Check splitrow.
      byte [] startKey = hri.getStartKey();
      byte [] endKey = hri.getEndKey();
      if (Bytes.equals(startKey, splitrow) ||
          !this.parent.getRegionInfo().containsRow(splitrow)) {
        LOG.info("Split row is not inside region key range or is equal to " +
          "startkey: " + Bytes.toString(this.splitrow));
        return prepared;
      }
      long rid = getDaughterRegionIdTimestamp(hri);
      this.hri_a = new HRegionInfo(hri.getTableDesc(), startKey, this.splitrow,
        false, rid);
      this.hri_b = new HRegionInfo(hri.getTableDesc(), this.splitrow, endKey,
        false, rid);
      prepared = true;
    } finally {
      if (!prepared) this.parent.lock.writeLock().unlock();
    }
    return prepared;
  }

  /**
   * Calculate daughter regionid to use.
   * @param hri Parent {@link HRegionInfo}
   * @return Daughter region id (timestamp) to use.
   */
  private static long getDaughterRegionIdTimestamp(final HRegionInfo hri) {
    long rid = EnvironmentEdgeManager.currentTimeMillis();
    // Regionid is timestamp.  Can't be less than that of parent else will insert
    // at wrong location in .META. (See HBASE-710).
    if (rid < hri.getRegionId()) {
      LOG.warn("Clock skew; parent regions id is " + hri.getRegionId() +
        " but current time here is " + rid);
      rid = hri.getRegionId() + 1;
    }
    return rid;
  }

  /**
   * Run the transaction.
   * @param server Hosting server instance.
   * @param services Used to online/offline regions.
   * @throws IOException If thrown, transaction failed. Call {@link #rollback(OnlineRegions)}
   * @return Regions created
   * @see #rollback(OnlineRegions)
   */
  PairOfSameType<HRegion> execute(final Server server,
      final RegionServerServices services)
  throws IOException {
    LOG.info("Starting split of region " + this.parent);
    if (!this.parent.lock.writeLock().isHeldByCurrentThread()) {
      throw new SplitAndCloseWriteLockNotHeld();
    }

    // If true, no cluster to write meta edits into.
    boolean testing =
      server.getConfiguration().getBoolean("hbase.testing.nocluster", false);

    createSplitDir(this.parent.getFilesystem(), this.splitdir);
    this.journal.add(JournalEntry.CREATE_SPLIT_DIR);

    List<StoreFile> hstoreFilesToSplit = this.parent.close(false);
    this.journal.add(JournalEntry.CLOSED_PARENT_REGION);

    if (!testing) {
      services.removeFromOnlineRegions(this.parent.getRegionInfo().getEncodedName());
    }
    this.journal.add(JournalEntry.OFFLINED_PARENT);

    splitStoreFiles(this.splitdir, hstoreFilesToSplit);
    // splitStoreFiles creates daughter region dirs under the parent splits dir
    // Nothing to unroll here if failure -- clean up of CREATE_SPLIT_DIR will
    // clean this up.

    // Log to the journal that we are creating region A, the first daughter
    // region.  We could fail halfway through.  If we do, we could have left
    // stuff in fs that needs cleanup -- a storefile or two.  Thats why we
    // add entry to journal BEFORE rather than AFTER the change.
    this.journal.add(JournalEntry.STARTED_REGION_A_CREATION);
    HRegion a = createDaughterRegion(this.hri_a, this.parent.flushRequester);

    // Ditto
    this.journal.add(JournalEntry.STARTED_REGION_B_CREATION);
    HRegion b = createDaughterRegion(this.hri_b, this.parent.flushRequester);

    // Edit parent in meta
    if (!testing) {
      MetaEditor.offlineParentInMeta(server.getCatalogTracker(),
        this.parent.getRegionInfo(), a.getRegionInfo(), b.getRegionInfo());
    }

    // The is the point of no return.  We are committed to the split now.  We
    // have still the daughter regions to open but meta has been changed.
    // If we fail from here on out, we can not rollback so, we'll just abort.
    // The meta has been changed though so there will need to be a fixup run
    // during processing of the crashed server by master (TODO: Verify this in place).

    // TODO: Could we be smarter about the sequence in which we do these steps?

    if (!testing) {
      // Open daughters in parallel.
      DaughterOpener aOpener = new DaughterOpener(server, services, a);
      DaughterOpener bOpener = new DaughterOpener(server, services, b);
      aOpener.start();
      bOpener.start();
      try {
        aOpener.join();
        bOpener.join();
      } catch (InterruptedException e) {
        server.abort("Exception running daughter opens", e);
      }
    }

    // Unlock if successful split.
    this.parent.lock.writeLock().unlock();

    // Leaving here, the splitdir with its dross will be in place but since the
    // split was successful, just leave it; it'll be cleaned when parent is
    // deleted and cleaned up.
    return new PairOfSameType<HRegion>(a, b);
  }

  class DaughterOpener extends Thread {
    private final RegionServerServices services;
    private final Server server;
    private final HRegion r;

    DaughterOpener(final Server s, final RegionServerServices services,
        final HRegion r) {
      super(s.getServerName() + "-daughterOpener=" + r.getRegionInfo().getEncodedName());
      setDaemon(true);
      this.services = services;
      this.server = s;
      this.r = r;
    }

    @Override
    public void run() {
      try {
        openDaughterRegion(this.server, this.services, r);
      } catch (Throwable t) {
        this.server.abort("Failed open of daughter " +
          this.r.getRegionInfo().getRegionNameAsString(), t);
      }
    }
  }

  /**
   * Open daughter regions, add them to online list and update meta.
   * @param server
   * @param services
   * @param daughter
   * @throws IOException
   * @throws KeeperException
   */
  void openDaughterRegion(final Server server,
      final RegionServerServices services, final HRegion daughter)
  throws IOException, KeeperException {
    HRegionInfo hri = daughter.getRegionInfo();
    LoggingProgressable reporter =
      new LoggingProgressable(hri, server.getConfiguration());
    HRegion r = daughter.openHRegion(reporter);
    services.postOpenDeployTasks(r, server.getCatalogTracker(), true);
  }

  static class LoggingProgressable implements Progressable {
    private final HRegionInfo hri;
    private long lastLog = -1;
    private final long interval;

    LoggingProgressable(final HRegionInfo hri, final Configuration c) {
      this.hri = hri;
      this.interval = c.getLong("hbase.regionserver.split.daughter.open.log.interval",
        10000);
    }

    @Override
    public void progress() {
      long now = System.currentTimeMillis();
      if (now - lastLog > this.interval) {
        LOG.info("Opening " + this.hri.getRegionNameAsString());
        this.lastLog = now;
      }
    }
  }

  private static Path getSplitDir(final HRegion r) {
    return new Path(r.getRegionDir(), SPLITDIR);
  }

  /**
   * @param fs Filesystem to use
   * @param splitdir Directory to store temporary split data in
   * @throws IOException If <code>splitdir</code> already exists or we fail
   * to create it.
   * @see #cleanupSplitDir(FileSystem, Path)
   */
  private static void createSplitDir(final FileSystem fs, final Path splitdir)
  throws IOException {
    if (fs.exists(splitdir)) throw new IOException("Splitdir already exits? " + splitdir);
    if (!fs.mkdirs(splitdir)) throw new IOException("Failed create of " + splitdir);
  }

  private static void cleanupSplitDir(final FileSystem fs, final Path splitdir)
  throws IOException {
    // Splitdir may have been cleaned up by reopen of the parent dir.
    deleteDir(fs, splitdir, false);
  }

  /**
   * @param fs Filesystem to use
   * @param dir Directory to delete
   * @param mustPreExist If true, we'll throw exception if <code>dir</code>
   * does not preexist, else we'll just pass.
   * @throws IOException Thrown if we fail to delete passed <code>dir</code>
   */
  private static void deleteDir(final FileSystem fs, final Path dir,
      final boolean mustPreExist)
  throws IOException {
    if (!fs.exists(dir)) {
      if (mustPreExist) throw new IOException(dir.toString() + " does not exist!");
    } else if (!fs.delete(dir, true)) {
      throw new IOException("Failed delete of " + dir);
    }
  }

  private void splitStoreFiles(final Path splitdir,
    final List<StoreFile> hstoreFilesToSplit)
  throws IOException {
    if (hstoreFilesToSplit == null) {
      // Could be null because close didn't succeed -- for now consider it fatal
      throw new IOException("Close returned empty list of StoreFiles");
    }

     // Split each store file.
     for (StoreFile sf: hstoreFilesToSplit) {
       splitStoreFile(sf, splitdir);
     }
  }

  private void splitStoreFile(final StoreFile sf, final Path splitdir)
  throws IOException {
    FileSystem fs = this.parent.getFilesystem();
    byte [] family = sf.getFamily();
    String encoded = this.hri_a.getEncodedName();
    Path storedir = Store.getStoreHomedir(splitdir, encoded, family);
    StoreFile.split(fs, storedir, sf, this.splitrow, Range.bottom);
    encoded = this.hri_b.getEncodedName();
    storedir = Store.getStoreHomedir(splitdir, encoded, family);
    StoreFile.split(fs, storedir, sf, this.splitrow, Range.top);
  }

  /**
   * @param hri Spec. for daughter region to open.
   * @param flusher Flusher this region should use.
   * @return Created daughter HRegion.
   * @throws IOException
   * @see #cleanupDaughterRegion(FileSystem, Path, HRegionInfo)
   */
  HRegion createDaughterRegion(final HRegionInfo hri,
      final FlushRequester flusher)
  throws IOException {
    // Package private so unit tests have access.
    FileSystem fs = this.parent.getFilesystem();
    Path regionDir = getSplitDirForDaughter(this.parent.getFilesystem(),
      this.splitdir, hri);
    HRegion r = HRegion.newHRegion(this.parent.getTableDir(),
      this.parent.getLog(), fs, this.parent.getConf(),
      hri, flusher);
    HRegion.moveInitialFilesIntoPlace(fs, regionDir, r.getRegionDir());
    return r;
  }

  private static void cleanupDaughterRegion(final FileSystem fs,
    final Path tabledir, final String encodedName)
  throws IOException {
    Path regiondir = HRegion.getRegionDir(tabledir, encodedName);
    // Dir may not preexist.
    deleteDir(fs, regiondir, false);
  }

  /*
   * Get the daughter directories in the splits dir.  The splits dir is under
   * the parent regions' directory.
   * @param fs
   * @param splitdir
   * @param hri
   * @return Path to daughter split dir.
   * @throws IOException
   */
  private static Path getSplitDirForDaughter(final FileSystem fs,
      final Path splitdir, final HRegionInfo hri)
  throws IOException {
    return new Path(splitdir, hri.getEncodedName());
  }

  /**
   * @param or Object that can online/offline parent region.  Can be passed null
   * by unit tests.
   * @return The region we were splitting
   * @throws IOException If thrown, rollback failed.  Take drastic action.
   */
  public void rollback(final OnlineRegions or) throws IOException {
    if (!this.parent.lock.writeLock().isHeldByCurrentThread()) {
      throw new SplitAndCloseWriteLockNotHeld();
    }
    FileSystem fs = this.parent.getFilesystem();
    ListIterator<JournalEntry> iterator =
      this.journal.listIterator(this.journal.size());
    while (iterator.hasPrevious()) {
      JournalEntry je = iterator.previous();
      switch(je) {
      case CREATE_SPLIT_DIR:
        cleanupSplitDir(fs, this.splitdir);
        break;

      case CLOSED_PARENT_REGION:
        // So, this returns a seqid but if we just closed and then reopened, we
        // should be ok. On close, we flushed using sequenceid obtained from
        // hosting regionserver so no need to propagate the sequenceid returned
        // out of initialize below up into regionserver as we normally do.
        // TODO: Verify.
        this.parent.initialize();
        break;

      case STARTED_REGION_A_CREATION:
        cleanupDaughterRegion(fs, this.parent.getTableDir(),
          this.hri_a.getEncodedName());
        break;

      case STARTED_REGION_B_CREATION:
        cleanupDaughterRegion(fs, this.parent.getTableDir(),
          this.hri_b.getEncodedName());
        break;

      case OFFLINED_PARENT:
        if (or != null) or.addToOnlineRegions(this.parent);
        break;

      default:
        throw new RuntimeException("Unhandled journal entry: " + je);
      }
    }
    if (this.parent.lock.writeLock().isHeldByCurrentThread()) {
      this.parent.lock.writeLock().unlock();
    }
  }

  /**
   * Thrown if lock not held.
   */
  @SuppressWarnings("serial")
  public class SplitAndCloseWriteLockNotHeld extends IOException {}

  HRegionInfo getFirstDaughter() {
    return hri_a;
  }

  HRegionInfo getSecondDaughter() {
    return hri_b;
  }

  // For unit testing.
  Path getSplitDir() {
    return this.splitdir;
  }

  /**
   * Clean up any split detritus that may have been left around from previous
   * split attempts.
   * Call this method on initial region deploy.  Cleans up any mess
   * left by previous deploys of passed <code>r</code> region.
   * @param r
   * @throws IOException 
   */
  static void cleanupAnySplitDetritus(final HRegion r) throws IOException {
    Path splitdir = getSplitDir(r);
    FileSystem fs = r.getFilesystem();
    if (!fs.exists(splitdir)) return;
    // Look at the splitdir.  It could have the encoded names of the daughter
    // regions we tried to make.  See if the daughter regions actually got made
    // out under the tabledir.  If here under splitdir still, then the split did
    // not complete.  Try and do cleanup.  This code WILL NOT catch the case
    // where we successfully created daughter a but regionserver crashed during
    // the creation of region b.  In this case, there'll be an orphan daughter
    // dir in the filesystem.  TOOD: Fix.
    FileStatus [] daughters = fs.listStatus(splitdir, new FSUtils.DirFilter(fs));
    for (int i = 0; i < daughters.length; i++) {
      cleanupDaughterRegion(fs, r.getTableDir(),
        daughters[i].getPath().getName());
    }
    cleanupSplitDir(r.getFilesystem(), splitdir);
    LOG.info("Cleaned up old failed split transaction detritus: " + splitdir);
  }
}
