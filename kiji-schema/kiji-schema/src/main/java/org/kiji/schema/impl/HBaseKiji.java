/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package org.kiji.schema.impl;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiAlreadyExistsException;
import org.kiji.schema.KijiMetaTable;
import org.kiji.schema.KijiNotInstalledException;
import org.kiji.schema.KijiRowKeySplitter;
import org.kiji.schema.KijiSchemaTable;
import org.kiji.schema.KijiSystemTable;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiURI;
import org.kiji.schema.avro.RowKeyEncoding;
import org.kiji.schema.avro.RowKeyFormat;
import org.kiji.schema.avro.TableLayoutDesc;
import org.kiji.schema.hbase.HBaseFactory;
import org.kiji.schema.hbase.KijiManagedHBaseTableName;
import org.kiji.schema.layout.InvalidLayoutException;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.impl.ColumnId;
import org.kiji.schema.layout.impl.HTableSchemaTranslator;
import org.kiji.schema.layout.impl.TableLayoutMonitor;
import org.kiji.schema.layout.impl.ZooKeeperClient;
import org.kiji.schema.util.Debug;
import org.kiji.schema.util.LockFactory;
import org.kiji.schema.util.ProtocolVersion;
import org.kiji.schema.util.ResourceUtils;
import org.kiji.schema.util.VersionInfo;

/**
 * Kiji instance class that contains configuration and table
 * information.  Multiple instances of Kiji can be installed onto a
 * single HBase cluster.  This class represents a single one of those
 * instances.
 */
@ApiAudience.Private
public final class HBaseKiji implements Kiji {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseKiji.class);
  private static final Logger CLEANUP_LOG =
      LoggerFactory.getLogger("cleanup." + HBaseKiji.class.getName());

  /** System version that introduces table layout in ZooKeeper. */
  private static final ProtocolVersion SYSTEM_2_0 = ProtocolVersion.parse("system-2.0");

  /** The hadoop configuration. */
  private final Configuration mConf;

  /** Factory for HTable instances. */
  private final HTableInterfaceFactory mHTableFactory;

  /** Factory for locks. */
  private final LockFactory mLockFactory;

  /** URI for this HBaseKiji instance. */
  private final KijiURI mURI;

  /** Whether the kiji instance is open. */
  private final AtomicBoolean mIsOpen = new AtomicBoolean(false);

  /** Retain counter. When decreased to 0, the HBase Kiji may be closed and disposed of. */
  private final AtomicInteger mRetainCount = new AtomicInteger(0);

  /**
   * String representation of the call stack at the time this object is constructed.
   * Used for debugging
   **/
  private final String mConstructorStack;

  /** HBase admin interface. Lazily initialized through {@link #getHBaseAdmin()}. */
  private HBaseAdmin mAdmin = null;

  /** The schema table for this kiji instance, or null if it has not been opened yet. */
  private HBaseSchemaTable mSchemaTable;

  /** The system table for this kiji instance. The system table is always open. */
  private final HBaseSystemTable mSystemTable;

  /** The meta table for this kiji instance, or null if it has not been opened yet. */
  private HBaseMetaTable mMetaTable;

  /** ZooKeeper client for this Kiji instance. */
  private final ZooKeeperClient mZKClient;

  /**
   * Creates a new <code>HBaseKiji</code> instance.
   *
   * <p> Should only be used by Kiji.Factory.open().
   * <p> Caller does not need to use retain(), but must call release() when done with it.
   *
   * @param kijiURI the KijiURI.
   * @param conf Hadoop Configuration. Deep copied internally.
   * @param tableFactory HTableInterface factory.
   * @param lockFactory Factory for locks.
   * @throws IOException on I/O error.
   */
  HBaseKiji(
      KijiURI kijiURI,
      Configuration conf,
      HTableInterfaceFactory tableFactory,
      LockFactory lockFactory)
      throws IOException {

    mConstructorStack = CLEANUP_LOG.isDebugEnabled() ? Debug.getStackTrace() : null;

    // Deep copy the configuration.
    mConf = new Configuration(conf);

    // Validate arguments.
    mHTableFactory = Preconditions.checkNotNull(tableFactory);
    mLockFactory = Preconditions.checkNotNull(lockFactory);
    mURI = Preconditions.checkNotNull(kijiURI);

    // Configure the ZooKeeper quorum:
    mConf.setStrings("hbase.zookeeper.quorum", mURI.getZookeeperQuorum().toArray(new String[0]));
    mConf.setInt("hbase.zookeeper.property.clientPort", mURI.getZookeeperClientPort());

    // Check for an instance name.
    Preconditions.checkArgument(mURI.getInstance() != null,
        "KijiURI '%s' does not specify a Kiji instance name.", mURI);
    LOG.debug(
        "Opening kiji instance '{}'"
        + " with client software version '{}'"
        + " and client data version '{}'.",
        mURI, VersionInfo.getSoftwareVersion(), VersionInfo.getClientDataVersion());

    // Load these lazily.
    mSchemaTable = null;
    mMetaTable = null;

    mSystemTable = new HBaseSystemTable(mURI, mConf, mHTableFactory);
    mIsOpen.set(true);
    LOG.debug("Kiji instance '{}' is now opened.", mURI);

    LOG.debug("Kiji instance '{}' has data version '{}'.", mURI, mSystemTable.getDataVersion());

    // Make sure the data version for the client matches the cluster.
    LOG.debug("Validating version for Kiji instance '{}'.", mURI);
    try {
      VersionInfo.validateVersion(this);
    } catch (IOException ioe) {
      // If an IOException occurred the object will not be constructed so need to clean it up.
      close();
      throw ioe;
    } catch (KijiNotInstalledException kie) {
      // Some clients handle this unchecked Exception so do the same here.
      close();
      throw kie;
    }

    // TODO(SCHEMA-491) Share ZooKeeperClient instances when possible.
    if (mSystemTable.getDataVersion().compareTo(SYSTEM_2_0) >= 0) {
      // system-2.0 clients must connect to ZooKeeper:
      //  - to register themselves as table users;
      //  - to receive table layout updates.
      final List<String> zkHosts = Lists.newArrayList();
      for (String host : mURI.getZookeeperQuorumOrdered()) {
        zkHosts.add(String.format("%s:%s", host, mURI.getZookeeperClientPort()));
      }
      final String zkAddress = Joiner.on(",").join(zkHosts);
      final int sessionTimeoutMS = 60 * 1000;
      mZKClient = new ZooKeeperClient(zkAddress, sessionTimeoutMS);
      mZKClient.open();
    } else {
      // system-1.x clients do not need a ZooKeeper connection.
      mZKClient = null;
    }

    mRetainCount.set(1);
  }

  /** {@inheritDoc} */
  @Override
  public Configuration getConf() {
    return mConf;
  }

  /** {@inheritDoc} */
  @Override
  public KijiURI getURI() {
    return mURI;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized KijiSchemaTable getSchemaTable() throws IOException {
    Preconditions.checkState(mIsOpen.get());
    if (null == mSchemaTable) {
      mSchemaTable = new HBaseSchemaTable(mURI, mConf, mHTableFactory, mLockFactory);
    }
    return mSchemaTable;
  }

  /** {@inheritDoc} */
  @Override
  public KijiSystemTable getSystemTable() {
    Preconditions.checkState(mIsOpen.get());
    return mSystemTable;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized KijiMetaTable getMetaTable() throws IOException {
    Preconditions.checkState(mIsOpen.get());
    if (null == mMetaTable) {
      mMetaTable = new HBaseMetaTable(mURI, mConf, getSchemaTable(), mHTableFactory);
    }
    return mMetaTable;
  }

  /**
   * Gets the current HBaseAdmin instance for this Kiji. This method will open a new
   * HBaseAdmin if one doesn't exist already.
   *
   * @throws IOException If there is an error opening the HBaseAdmin.
   * @return The current HBaseAdmin instance for this Kiji.
   */
  public synchronized HBaseAdmin getHBaseAdmin() throws IOException {
    Preconditions.checkState(mIsOpen.get());
    if (null == mAdmin) {
      final HBaseFactory hbaseFactory = HBaseFactory.Provider.get();
      mAdmin = hbaseFactory.getHBaseAdminFactory(mURI).create(getConf());
    }
    return mAdmin;
  }

  /** {@inheritDoc} */
  @Override
  public KijiTable openTable(String tableName) throws IOException {
    Preconditions.checkState(mIsOpen.get(), "HBaseKiji %s is closed", this);
    return new HBaseKijiTable(this, tableName, mConf, mHTableFactory);
  }

  /** {@inheritDoc} */
  @Deprecated
  @Override
  public void createTable(String tableName, KijiTableLayout tableLayout)
      throws IOException {
    if (!tableName.equals(tableLayout.getName())) {
      throw new RuntimeException(String.format(
          "Table name from layout descriptor '%s' does match table name '%s'.",
          tableLayout.getName(), tableName));
    }

    createTable(tableLayout.getDesc());
  }

  /** {@inheritDoc} */
  @Override
  public void createTable(TableLayoutDesc tableLayout)
      throws IOException {
    createTable(tableLayout, 1);
  }

  /** {@inheritDoc} */
  @Deprecated
  @Override
  public void createTable(String tableName, KijiTableLayout tableLayout, int numRegions)
      throws IOException {
    if (!tableName.equals(tableLayout.getName())) {
      throw new RuntimeException(String.format(
          "Table name from layout descriptor '%s' does match table name '%s'.",
          tableLayout.getName(), tableName));
    }

    createTable(tableLayout.getDesc(), numRegions);
  }

  /** {@inheritDoc} */
  @Override
  public void createTable(TableLayoutDesc tableLayout, int numRegions)
      throws IOException {
    Preconditions.checkArgument((numRegions >= 1), "numRegions must be positive: " + numRegions);
    if (numRegions > 1) {
      if (KijiTableLayout.getEncoding(tableLayout.getKeysFormat())
          == RowKeyEncoding.RAW) {
        throw new IllegalArgumentException(
            "May not use numRegions > 1 if row key hashing is disabled in the layout");
      }

      createTable(tableLayout, KijiRowKeySplitter.get().getSplitKeys(numRegions,
          KijiRowKeySplitter.getRowKeyResolution(tableLayout)));
    } else {
      createTable(tableLayout, null);
    }
  }

  /** {@inheritDoc} */
  @Deprecated
  @Override
  public void createTable(String tableName, KijiTableLayout tableLayout, byte[][] splitKeys)
      throws IOException {
    if (getMetaTable().tableExists(tableName)) {
      final KijiURI tableURI =
          KijiURI.newBuilder(mURI).withTableName(tableName).build();
      throw new KijiAlreadyExistsException(String.format(
          "Kiji table '%s' already exists.", tableURI), tableURI);
    }

    if (!tableName.equals(tableLayout.getName())) {
      throw new RuntimeException(String.format(
          "Table name from layout descriptor '%s' does match table name '%s'.",
          tableLayout.getName(), tableName));
    }

    createTable(tableLayout.getDesc(), splitKeys);
  }

  /** {@inheritDoc} */
  @Override
  public void createTable(TableLayoutDesc tableLayout, byte[][] splitKeys) throws IOException {
    final KijiURI tableURI = KijiURI.newBuilder(mURI).withTableName(tableLayout.getName()).build();
    LOG.debug("Creating Kiji table '{}'.", tableURI);

    // This will validate the layout and may throw an InvalidLayoutException.
    final KijiTableLayout kijiTableLayout = KijiTableLayout.newLayout(tableLayout);

    if (getMetaTable().tableExists(tableLayout.getName())) {
      throw new KijiAlreadyExistsException(
          String.format("Kiji table '%s' already exists.", tableURI), tableURI);
    }

    if (tableLayout.getKeysFormat() instanceof RowKeyFormat) {
      LOG.warn("Usage of 'RowKeyFormat' is deprecated. New tables should use 'RowKeyFormat2'.");
    }

    getMetaTable().updateTableLayout(tableLayout.getName(), tableLayout);

    if (getSystemTable().getDataVersion().compareTo(SYSTEM_2_0) >= 0) {
      // system-2.0 clients retrieve the table layout from ZooKeeper as a stream of notifications.
      // Invariant: ZooKeeper hold the most recent layout of the table.
      LOG.debug("Writing initial table layout in ZooKeeper for table {}.", tableURI);
      try {
        final TableLayoutMonitor monitor = new TableLayoutMonitor(mZKClient);
        try {
          final byte[] layoutId = Bytes.toBytes(kijiTableLayout.getDesc().getLayoutId());
            monitor.notifyNewTableLayout(tableURI, layoutId, -1);
        } finally {
          monitor.close();
        }
      } catch (KeeperException ke) {
        throw new IOException(ke);
      }
    }

    try {
      final HTableSchemaTranslator translator = new HTableSchemaTranslator();
      final HTableDescriptor desc =
          translator.toHTableDescriptor(mURI.getInstance(), kijiTableLayout);
      LOG.debug("Creating HBase table '{}'.", desc.getNameAsString());
      if (null != splitKeys) {
        getHBaseAdmin().createTable(desc, splitKeys);
      } else {
        getHBaseAdmin().createTable(desc);
      }
    } catch (TableExistsException tee) {
      throw new KijiAlreadyExistsException(
          String.format("Kiji table '%s' already exists.", tableURI), tableURI);
    }
  }

  /** {@inheritDoc} */
  @Deprecated
  @Override
  public KijiTableLayout modifyTableLayout(String tableName, TableLayoutDesc update)
      throws IOException {
    if (!tableName.equals(update.getName())) {
      throw new InvalidLayoutException(String.format(
          "Name of table in descriptor '%s' does not match table name '%s'.",
          update.getName(), tableName));
    }

    return modifyTableLayout(update);
  }

  /** {@inheritDoc} */
  @Override
  public KijiTableLayout modifyTableLayout(TableLayoutDesc update) throws IOException {
    return modifyTableLayout(update, false, null);
  }

  /** {@inheritDoc} */
  @Deprecated
  @Override
  public KijiTableLayout modifyTableLayout(
      String tableName,
      TableLayoutDesc update,
      boolean dryRun,
      PrintStream printStream)
      throws IOException {
    if (!tableName.equals(update.getName())) {
      throw new InvalidLayoutException(String.format(
          "Name of table in descriptor '%s' does not match table name '%s'.",
          update.getName(), tableName));
    }

    return modifyTableLayout(update, dryRun, printStream);
  }

  /** {@inheritDoc} */
  @Override
  public KijiTableLayout modifyTableLayout(
      TableLayoutDesc update,
      boolean dryRun,
      PrintStream printStream)
      throws IOException {
    Preconditions.checkState(mIsOpen.get(), "HBaseKiji %s is closed", this);
    Preconditions.checkNotNull(update);

    if (dryRun && (null == printStream)) {
      printStream = System.out;
    }

    final KijiMetaTable metaTable = getMetaTable();

    // Try to get the table layout first, which will throw a KijiTableNotFoundException if
    // there is no table.
    final String tableName = update.getName();
    metaTable.getTableLayout(tableName);

    final KijiURI tableURI = KijiURI.newBuilder(mURI).withTableName(tableName).build();
    LOG.debug("Applying layout update {} on table {}", update, tableURI);

    KijiTableLayout newLayout = null;

    if (dryRun) {
      // Process column ids and perform validation, but don't actually update the meta table.
      final List<KijiTableLayout> layouts = metaTable.getTableLayoutVersions(tableName, 1);
      final KijiTableLayout currentLayout = layouts.isEmpty() ? null : layouts.get(0);
      newLayout = KijiTableLayout.createUpdatedLayout(update, currentLayout);
    } else {
      // Actually set it.
      if (mSystemTable.getDataVersion().compareTo(SYSTEM_2_0) >= 0) {
        try {
          final HBaseTableLayoutUpdater updater =
              new HBaseTableLayoutUpdater(this, tableURI, update);
          updater.update();
          newLayout = updater.getNewLayout();

        } catch (KeeperException ke) {
          throw new IOException(ke);
        }
      } else {
        newLayout = metaTable.updateTableLayout(tableName, update);
      }
    }
    Preconditions.checkState(newLayout != null);

    if (dryRun) {
      printStream.println("This table layout is valid.");
    }

    LOG.debug("Computing new HBase schema");
    final HTableSchemaTranslator translator = new HTableSchemaTranslator();
    final HTableDescriptor newTableDescriptor =
        translator.toHTableDescriptor(mURI.getInstance(), newLayout);

    LOG.debug("Reading existing HBase schema");
    final KijiManagedHBaseTableName hbaseTableName =
        KijiManagedHBaseTableName.getKijiTableName(mURI.getInstance(), tableName);
    HTableDescriptor currentTableDescriptor = null;
    byte[] tableNameAsBytes = hbaseTableName.toBytes();
    try {
      currentTableDescriptor = getHBaseAdmin().getTableDescriptor(tableNameAsBytes);
    } catch (TableNotFoundException tnfe) {
      if (!dryRun) {
        throw tnfe; // Not in dry-run mode; table needs to exist. Rethrow exception.
      }
    }
    if (currentTableDescriptor == null) {
      if (dryRun) {
        printStream.println("Would create new table: " + tableName);
        currentTableDescriptor = HTableDescriptorComparator.makeEmptyTableDescriptor(
            hbaseTableName);
      } else {
        throw new RuntimeException("Table " + hbaseTableName.getKijiTableName()
            + " does not exist");
      }
    }
    LOG.debug("Existing table descriptor: {}", currentTableDescriptor);
    LOG.debug("New table descriptor: {}", newTableDescriptor);

    LOG.debug("Checking for differences between the new HBase schema and the existing one");
    final HTableDescriptorComparator comparator = new HTableDescriptorComparator();
    if (0 == comparator.compare(currentTableDescriptor, newTableDescriptor)) {
      LOG.debug("HBase schemas are the same.  No need to change HBase schema");
      if (dryRun) {
        printStream.println("This layout does not require any physical table schema changes.");
      }
    } else {
      LOG.debug("HBase schema must be changed, but no columns will be deleted");

      if (dryRun) {
        printStream.println("Changes caused by this table layout:");
      } else {
        LOG.debug("Disabling HBase table");
        getHBaseAdmin().disableTable(hbaseTableName.toString());
      }

      for (HColumnDescriptor newColumnDescriptor : newTableDescriptor.getFamilies()) {
        final String columnName = Bytes.toString(newColumnDescriptor.getName());
        final ColumnId columnId = ColumnId.fromString(columnName);
        final String lgName = newLayout.getLocalityGroupIdNameMap().get(columnId);
        final HColumnDescriptor currentColumnDescriptor =
            currentTableDescriptor.getFamily(newColumnDescriptor.getName());
        if (null == currentColumnDescriptor) {
          if (dryRun) {
            printStream.println("  Creating new locality group: " + lgName);
          } else {
            LOG.debug("Creating new column " + columnName);
            getHBaseAdmin().addColumn(hbaseTableName.toString(), newColumnDescriptor);
          }
        } else if (!newColumnDescriptor.equals(currentColumnDescriptor)) {
          if (dryRun) {
            printStream.println("  Modifying locality group: " + lgName);
          } else {
            LOG.debug("Modifying column " + columnName);
            getHBaseAdmin().modifyColumn(hbaseTableName.toString(), newColumnDescriptor);
          }
        } else {
          LOG.debug("No changes needed for column " + columnName);
        }
      }

      if (dryRun) {
        if (newTableDescriptor.getMaxFileSize() != currentTableDescriptor.getMaxFileSize()) {
          printStream.printf("  Changing max_filesize from %d to %d: %n",
            currentTableDescriptor.getMaxFileSize(),
            newTableDescriptor.getMaxFileSize());
        }
        if (newTableDescriptor.getMaxFileSize() != currentTableDescriptor.getMaxFileSize()) {
          printStream.printf("  Changing memstore_flushsize from %d to %d: %n",
            currentTableDescriptor.getMemStoreFlushSize(),
            newTableDescriptor.getMemStoreFlushSize());
        }
      } else {
        LOG.debug("Modifying table descriptor");
        getHBaseAdmin().modifyTable(tableNameAsBytes, newTableDescriptor);
      }

      if (!dryRun) {
        LOG.debug("Re-enabling HBase table");
        getHBaseAdmin().enableTable(hbaseTableName.toString());
      }
    }

    return newLayout;
  }

  /** {@inheritDoc} */
  @Override
  public void deleteTable(String tableName) throws IOException {
    Preconditions.checkState(mIsOpen.get(), "HBaseKiji %s is closed", this);
    // Delete from HBase.
    String hbaseTable = KijiManagedHBaseTableName.getKijiTableName(mURI.getInstance(),
        tableName).toString();
    getHBaseAdmin().disableTable(hbaseTable);
    getHBaseAdmin().deleteTable(hbaseTable);

    // Delete from the meta table.
    getMetaTable().deleteTable(tableName);

    // If the table persists immediately after deletion attempt, then give up.
    if (getHBaseAdmin().tableExists(hbaseTable)) {
      LOG.warn("HBase table " + hbaseTable + " survives deletion attempt. Giving up...");
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<String> getTableNames() throws IOException {
    Preconditions.checkState(mIsOpen.get(), "HBaseKiji %s is closed", this);
    return getMetaTable().listTables();
  }

  /**
   * Releases all the resources used by this Kiji instance.
   *
   * @throws IOException on I/O error.
   */
  private void close() throws IOException {
    if (!mIsOpen.getAndSet(false)) {
      LOG.error("Cannot close {} : not open.", this);
      return;
    }

    if (mZKClient != null) {
      mZKClient.release();
    }

    LOG.debug("Closing {}.", this);
    ResourceUtils.closeOrLog(mMetaTable);
    ResourceUtils.closeOrLog(mSystemTable);
    ResourceUtils.closeOrLog(mSchemaTable);
    ResourceUtils.closeOrLog(mAdmin);
    mSchemaTable = null;
    mMetaTable = null;
    mAdmin = null;
    LOG.debug("{} closed.", this);
  }

  /** {@inheritDoc} */
  @Override
  public Kiji retain() {
    final int counter = mRetainCount.getAndIncrement();
    Preconditions.checkState(counter >= 1,
        "Cannot retain closed Kiji %s: retain counter was %s.", mURI, counter);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public void release() throws IOException {
    final int counter = mRetainCount.decrementAndGet();
    Preconditions.checkState(counter >= 0,
        "Cannot release closed Kiji %s: retain counter is now %s.", mURI, counter);
    if (counter == 0) {
      close();
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(Object obj) {
    if (null == obj) {
      return false;
    }
    if (obj == this) {
      return true;
    }
    if (!getClass().equals(obj.getClass())) {
      return false;
    }
    final Kiji other = (Kiji) obj;

    // Equal if the two instances have the same URI:
    return mURI.equals(other.getURI());
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return mURI.hashCode();
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() throws Throwable {
    if (mIsOpen.get()) {
      CLEANUP_LOG.warn(
          "Finalizing opened resource '{}' with {} retained references. "
              + "You must release() it.",
          mURI,
          mRetainCount.get());
      if (CLEANUP_LOG.isDebugEnabled()) {
        CLEANUP_LOG.debug(
            "HBaseKiji '{}' was constructed through:\n{}",
            mURI,
            mConstructorStack);
      }
      close();
    }
    super.finalize();
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return Objects.toStringHelper(HBaseKiji.class)
        .add("id", System.identityHashCode(this))
        .add("uri", mURI)
        .add("retain-count", mRetainCount)
        .add("open", mIsOpen)
        .toString();
  }

  /**
   * Returns the ZooKeeper client for this Kiji instance.
   *
   * @return the ZooKeeper client for this Kiji instance.
   *     Null if the data version &le; {@code system-2.0}.
   */
  ZooKeeperClient getZKClient() {
    return mZKClient;
  }
}
