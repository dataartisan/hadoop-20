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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.FSConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeMetrics;
import org.apache.hadoop.io.IOUtils;

import org.apache.log4j.Level;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Test various failure scenarios during saveNamespace() operation.
 * Cases covered:
 * <ol>
 * <li>Recover from failure while saving into the second storage directory</li>
 * <li>Recover from failure while moving current into lastcheckpoint.tmp</li>
 * <li>Recover from failure while moving lastcheckpoint.tmp into
 * previous.checkpoint</li>
 * <li>Recover from failure while rolling edits file</li>
 * </ol>
 */
public class TestSaveNamespace {
  static {
    ((Log4JLogger)FSImage.LOG).getLogger().setLevel(Level.ALL);
  }
  
  private static final Log LOG = LogFactory.getLog(TestSaveNamespace.class);

  private static class FaultySaveImage implements Answer<Void> {
    int count = 0;
    boolean throwRTE = true;

    // generate either a RuntimeException or IOException
    public FaultySaveImage(boolean throwRTE) {
      this.throwRTE = throwRTE;
    }

    public Void answer(InvocationOnMock invocation) throws Throwable {
      Object[] args = invocation.getArguments();
      StorageDirectory sd = (StorageDirectory)args[1];

      if (count++ == 1) {
        LOG.info("Injecting fault for sd: " + sd);
        if (throwRTE) {
          throw new RuntimeException("Injected fault: saveFSImage second time");
        } else {
          throw new IOException("Injected fault: saveFSImage second time");
        }
      }
      LOG.info("Not injecting fault for sd: " + sd);
      return (Void)invocation.callRealMethod();
    }
  }

  private enum Fault {
    SAVE_SECOND_FSIMAGE_RTE,
    SAVE_SECOND_FSIMAGE_IOE,
    SAVE_ALL_FSIMAGES,
    WRITE_STORAGE_ALL,
    WRITE_STORAGE_ONE
  };

  private void saveNamespaceWithInjectedFault(Fault fault) throws Exception {
    Configuration conf = getConf();
    NameNode.myMetrics = new NameNodeMetrics(conf, null);
    NameNode.format(conf);
    NameNode nn = new NameNode(conf);
    FSNamesystem fsn = nn.getNamesystem();

    // Replace the FSImage with a spy
    FSImage originalImage = fsn.dir.fsImage;
    NNStorage storage = originalImage.storage;

    NNStorage spyStorage = spy(storage);
    originalImage.storage = spyStorage;

    FSImage spyImage = spy(originalImage);
    fsn.dir.fsImage = spyImage;

    boolean shouldFail = false; // should we expect the save operation to fail
    // inject fault
    switch(fault) {
    case SAVE_SECOND_FSIMAGE_RTE:
      // The spy throws a RuntimeException when writing to the second directory
      doAnswer(new FaultySaveImage(true)).
        when(spyImage).saveFSImage(
            (SaveNamespaceContext)anyObject(),
            (StorageDirectory)anyObject(), anyBoolean());
      shouldFail = false;
      break;
    case SAVE_SECOND_FSIMAGE_IOE:
      // The spy throws an IOException when writing to the second directory
      doAnswer(new FaultySaveImage(false)).
        when(spyImage).saveFSImage(
            (SaveNamespaceContext)anyObject(),
            (StorageDirectory)anyObject(), anyBoolean());
      shouldFail = false;
      break;
    case SAVE_ALL_FSIMAGES:
      // The spy throws IOException in all directories
      doThrow(new RuntimeException("Injected")).
      when(spyImage).saveFSImage(
          (SaveNamespaceContext)anyObject(),
          (StorageDirectory)anyObject(), anyBoolean());
      shouldFail = true;
      break;
    case WRITE_STORAGE_ALL:
      // The spy throws an exception before writing any VERSION files
      doThrow(new RuntimeException("Injected"))
        .when(spyStorage).writeAll();
      shouldFail = true;
      break;
    }

    try {
      doAnEdit(fsn, 1);

      // Save namespace - this may fail, depending on fault injected
      fsn.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      try {
        fsn.saveNamespace(false, false);
        if (shouldFail) {
          fail("Did not fail!");
        }
      } catch (Exception e) {
        if (! shouldFail) {
          throw e;
        } else {
          LOG.info("Test caught expected exception", e);
        }
      }
      
      fsn.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      // Should still be able to perform edits
      doAnEdit(fsn, 2);

      // Now shut down and restart the namesystem
      originalImage.close();
      fsn.close();      
      fsn = null;

      // Start a new namesystem, which should be able to recover
      // the namespace from the previous incarnation.
      nn.stop();
      nn = new NameNode(conf);
      fsn = nn.getNamesystem();

      // Make sure the image loaded including our edits.
      checkEditExists(fsn, 1);
      checkEditExists(fsn, 2);
    } finally {
      if (fsn != null) {
        fsn.close();
      }
    }
  }

  /**
   * Verify that a saveNamespace command brings faulty directories
   * in fs.name.dir and fs.edit.dir back online.
   */
  @Test
  public void testReinsertnamedirsInSavenamespace() throws Exception {
    // create a configuration with the key to restore error
    // directories in fs.name.dir
    Configuration conf = getConf();

    MiniDFSCluster cluster = new MiniDFSCluster(conf, 1, true, null);
    cluster.waitActive();
    FSNamesystem fsn = cluster.getNameNode().getNamesystem();

    // Replace the FSImage with a spy
    FSImage originalImage = fsn.dir.fsImage;
    FSImage spyImage = spy(originalImage);
    long mrctxid = originalImage.storage.getMostRecentCheckpointTxId();
    spyImage.setImageDigest(mrctxid ,originalImage.getImageDigest(mrctxid));
    fsn.dir.fsImage = spyImage;
    
    File rootDir = spyImage.storage.getStorageDir(0).getRoot();
    rootDir.setExecutable(false);
    rootDir.setWritable(false);
    rootDir.setReadable(false);


    try {
      doAnEdit(fsn, 1);
      fsn.setSafeMode(SafeModeAction.SAFEMODE_ENTER);

      // Save namespace - this  injects a fault and marks one
      // directory as faulty.
      LOG.info("Doing the first savenamespace.");
      fsn.saveNamespace(false, false);
      LOG.warn("First savenamespace sucessful.");
      assertTrue("Savenamespace should have marked one directory as bad." +
                 " But found " + spyImage.getRemovedStorageDirs().size() +
                 " bad directories.",
                   spyImage.getRemovedStorageDirs().size() == 1);

      rootDir.setExecutable(true);
      rootDir.setWritable(true);
      rootDir.setReadable(true);
      
      // The next call to savenamespace should try inserting the
      // erroneous directory back to fs.name.dir. This command should
      // be successful.
      LOG.info("Doing the second savenamespace.");
      fsn.saveNamespace(false, false);
      LOG.warn("Second savenamespace sucessful.");
      assertTrue("Savenamespace should have been successful in removing " +
                 " bad directories from Image."  +
                 " But found " + originalImage.getRemovedStorageDirs().size() +
                 " bad directories.",
                 originalImage.getRemovedStorageDirs().size() == 0);

      // Now shut down and restart the namesystem
      LOG.info("Shutting down fsimage.");
      originalImage.close();
      fsn.close();
      fsn = null;
      cluster.shutdown();

      // Start a new namesystem, which should be able to recover
      // the namespace from the previous incarnation.
      LOG.info("Loading new FSmage from disk.");
      cluster = new MiniDFSCluster(conf, 1, false, null);
      cluster.waitActive();
      fsn = cluster.getNameNode().getNamesystem();

      // Make sure the image loaded including our edit.
      LOG.info("Checking reloaded image.");
      checkEditExists(cluster.getNameNode().namesystem, 1);
      LOG.info("Reloaded image is good.");
    } finally {
      fsn.close();
      cluster.shutdown();
    }
  }

  @Test
  public void testRTEWhileSavingSecondImage() throws Exception {
    saveNamespaceWithInjectedFault(Fault.SAVE_SECOND_FSIMAGE_RTE);
  }

  @Test
  public void testIOEWhileSavingSecondImage() throws Exception {
    saveNamespaceWithInjectedFault(Fault.SAVE_SECOND_FSIMAGE_IOE);
  }

  @Test
  public void testCrashInAllImageDirs() throws Exception {
    saveNamespaceWithInjectedFault(Fault.SAVE_ALL_FSIMAGES);
  }
  
  @Test
  public void testCrashWhenWritingVersionFiles() throws Exception {
    saveNamespaceWithInjectedFault(Fault.WRITE_STORAGE_ALL);
  }
 

  /**
   * Test case where savenamespace fails in all directories
   * and then the NN shuts down. Here we should recover from the
   * failed checkpoint since it only affected ".ckpt" files, not
   * valid image files
   */
  @Test
  public void testFailedSaveNamespace() throws Exception {
    doTestFailedSaveNamespace(false);
  }

  /**
   * Test case where saveNamespace fails in all directories, but then
   * the operator restores the directories and calls it again.
   * This should leave the NN in a clean state for next start.
   */
  @Test
  public void testFailedSaveNamespaceWithRecovery() throws Exception {
    doTestFailedSaveNamespace(true);
  }

  /**
   * Injects a failure on all storage directories while saving namespace.
   *
   * @param restoreStorageAfterFailure if true, will try to save again after
   *   clearing the failure injection
   */
  private void doTestFailedSaveNamespace(boolean restoreStorageAfterFailure)
  throws Exception {
    Configuration conf = getConf();
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 0, true, null);
    cluster.waitActive();
    FSNamesystem fsn = cluster.getNameNode().getNamesystem();

    // Replace the FSImage with a spy
    final FSImage originalImage = fsn.dir.fsImage;
    NNStorage storage = originalImage.storage;
    storage.close(); // unlock any directories that FSNamesystem's initialization may have locked

    NNStorage spyStorage = spy(storage);
    originalImage.storage = spyStorage;
    FSImage spyImage = spy(originalImage);
    fsn.dir.fsImage = spyImage;
    spyImage.storage.setStorageDirectories(
        NNStorageConfiguration.getNamespaceDirs(conf), 
        NNStorageConfiguration.getNamespaceEditsDirs(conf));

    doThrow(new IOException("Injected fault: saveFSImage")).
        when(spyImage).saveFSImage(
            (SaveNamespaceContext)anyObject(),
            (StorageDirectory)anyObject(), anyBoolean());

    try {
      doAnEdit(fsn, 1);

      // Save namespace
      fsn.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      try {
        fsn.saveNamespace(false, false);
        fail("saveNamespace did not fail even when all directories failed!");
      } catch (IOException ioe) {
        LOG.info("Got expected exception", ioe);
      }
      
      // Ensure that, if storage dirs come back online, things work again.
      if (restoreStorageAfterFailure) {
        Mockito.reset(spyImage);
        fsn.saveNamespace(false, false);
        checkEditExists(fsn, 1);
      }

      // Now shut down and restart the NN
      originalImage.close();
      fsn.close();
      fsn = null;

      // Start a new namesystem, which should be able to recover
      // the namespace from the previous incarnation.
      cluster = new MiniDFSCluster(conf, 0, false, null);
      cluster.waitActive();
      fsn = cluster.getNameNode().getNamesystem();

      // Make sure the image loaded including our edits.
      checkEditExists(fsn, 1);
    } finally {
      if (fsn != null) {
        fsn.close();
      }
    }
  }

  @Test
  public void testSaveWhileEditsRolled() throws Exception {
    Configuration conf = getConf();
    NameNode.myMetrics = new NameNodeMetrics(conf, null);
    NameNode.format(conf);
    NameNode nn = new NameNode(conf);
    FSNamesystem fsn = nn.getNamesystem();

    // Replace the FSImage with a spy
    final FSImage originalImage = fsn.dir.fsImage;
    FSImage spyImage = spy(originalImage);
    fsn.dir.fsImage = spyImage;

    try {
      doAnEdit(fsn, 1);
      CheckpointSignature sig = fsn.rollEditLog();
      LOG.warn("Checkpoint signature: " + sig);
      // Do another edit
      doAnEdit(fsn, 2);

      // Save namespace
      fsn.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fsn.saveNamespace(false, false);

      // Now shut down and restart the NN
      nn.stop();
      nn = null;

      // Start a new namesystem, which should be able to recover
      // the namespace from the previous incarnation.
      nn = new NameNode(conf);
      fsn = nn.getNamesystem();

      // Make sure the image loaded including our edits.
      checkEditExists(fsn, 1);
      checkEditExists(fsn, 2);
    } finally {
      if (nn != null) {
        nn.stop();
      }
    }
  }
  
  @Test
  public void testTxIdPersistence() throws Exception {
    Configuration conf = getConf();
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 0, true, null);
    cluster.waitActive();
    FSNamesystem fsn = cluster.getNameNode().getNamesystem();

    try {
      // We have a BEGIN_LOG_SEGMENT txn to start
      assertEquals(0, fsn.getEditLog().getLastWrittenTxId());
      doAnEdit(fsn, 1);
      assertEquals(1, fsn.getEditLog().getLastWrittenTxId());
      
      fsn.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fsn.saveNamespace(false, false);

      // 2 more txns: END the first segment, BEGIN a new one
      assertEquals(3, fsn.getEditLog().getLastWrittenTxId());
      
      // Shut down and restart
      fsn.getFSImage().close();
      fsn.close();
      
      // 1 more txn to END that segment
      assertEquals(4, fsn.getEditLog().getLastWrittenTxId());
      fsn = null;
      
      cluster = new MiniDFSCluster(conf, 0, false, null);
      cluster.waitActive();
      fsn = cluster.getNameNode().getNamesystem();
      
      // 1 more txn to start new segment on restart
      assertEquals(5, fsn.getEditLog().getLastWrittenTxId());
      
    } finally {
      if (fsn != null) {
        fsn.close();
      }
    }
  }

  /**
   * Test for save namespace should succeed when parent directory renamed with
   * open lease and destination directory exist. 
   * This test is a regression for HDFS-2827
   */
  @Test
  public void testSaveNamespaceWithRenamedLease() throws Exception {
    MiniDFSCluster cluster = new MiniDFSCluster(new Configuration(), 1, true, (String[]) null);
    cluster.waitActive();
    DistributedFileSystem fs = (DistributedFileSystem) cluster.getFileSystem();
    OutputStream out = null;
    try {
      fs.mkdirs(new Path("/test-target"));
      out = fs.create(new Path("/test-source/foo")); // don't close
      fs.rename(new Path("/test-source/"), new Path("/test-target/"));

      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      cluster.getNameNode().saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
    } finally {
      IOUtils.cleanup(LOG, out, fs);
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
  
  private void doAnEdit(FSNamesystem fsn, int id) throws IOException {
    // Make an edit
    fsn.mkdirs(
      "/test" + id,
      new PermissionStatus("test", "Test",
          new FsPermission((short)0777)));
  }

  private void checkEditExists(FSNamesystem fsn, int id) throws IOException {
    // Make sure the image loaded including our edit.
    assertNotNull(fsn.getFileInfo("/test" + id));
  }
  
  private Configuration getConf() throws IOException {
    String baseDir = System.getProperty("test.build.data", "build/test/data/dfs/");
    String nameDirs = baseDir + "name1" + "," + baseDir + "name2";
    Configuration conf = new Configuration();
    FileSystem.setDefaultUri(conf, "hdfs://localhost:0");
    conf.set("dfs.http.address", "0.0.0.0:0");
    conf.set("dfs.name.dir", nameDirs);
    conf.set("dfs.name.edits.dir", nameDirs);
    conf.set("dfs.secondary.http.address", "0.0.0.0:0");
    conf.setBoolean("dfs.permissions", false); 
    return conf;
  }
}
