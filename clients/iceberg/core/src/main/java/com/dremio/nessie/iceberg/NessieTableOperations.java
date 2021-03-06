/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.nessie.iceberg;

import java.lang.reflect.Method;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;

import com.dremio.nessie.client.NessieClient;
import com.dremio.nessie.error.NessieConflictException;
import com.dremio.nessie.error.NessieNotFoundException;
import com.dremio.nessie.model.Contents;
import com.dremio.nessie.model.ContentsKey;
import com.dremio.nessie.model.IcebergTable;
import com.dremio.nessie.model.ImmutableIcebergTable;

/**
 * Nessie implementation of Iceberg TableOperations.
 */
public class NessieTableOperations extends BaseMetastoreTableOperations {

  private static Method sparkConfMethod;
  private static Method appIdMethod;
  private static Method sparkEnvMethod;

  private final Configuration conf;
  private final NessieClient client;
  private final ContentsKey key;
  private UpdateableReference reference;
  private IcebergTable table;
  private HadoopFileIO fileIO;

  /**
   * Create a nessie table operations given a table identifier.
   */
  public NessieTableOperations(
      Configuration conf,
      ContentsKey key,
      UpdateableReference reference,
      NessieClient client) {
    this.conf = conf;
    this.key = key;
    this.reference = reference;
    this.client = client;
  }

  @Override
  protected void doRefresh() {
    // break reference with parent (to avoid cross-over refresh)
    // TODO, confirm this is correct behavior.
    //reference = reference.clone();

    reference.refresh();
    String metadataLocation = null;
    try {
      Contents c = client.getContentsApi().getContents(key, reference.getHash());
      this.table = c.unwrap(IcebergTable.class)
          .orElseThrow(() -> new IllegalStateException("Nessie points to a non-Iceberg object for that path."));
      metadataLocation = table.getMetadataLocation();
    } catch (NessieNotFoundException ex) {
      this.table = null;
    }
    refreshFromMetadataLocation(metadataLocation, 2);
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    reference.checkMutable();

    String newMetadataLocation = writeNewMetadata(metadata, currentVersion() + 1);

    try {
      IcebergTable table = ImmutableIcebergTable.builder().metadataLocation(newMetadataLocation).build();
      client.getContentsApi().setContents(key,
                                          reference.getAsBranch().getName(),
                                          reference.getHash(),
                                          String.format("iceberg commit%s", applicationId()),
                                          table);
    } catch (NessieNotFoundException | NessieConflictException ex) {
      io().deleteFile(newMetadataLocation);
      throw new CommitFailedException(ex, "failed");
    } catch (Throwable e) {
      io().deleteFile(newMetadataLocation);
      throw new RuntimeException("Unexpected commit exception", e);
    }
  }

  @Override
  public FileIO io() {
    if (fileIO == null) {
      fileIO = new HadoopFileIO(conf);
    }

    return fileIO;
  }

  /**
   * try and get a Spark application id if one exists.
   *
   * <p>
   *   We haven't figured out a general way to pass commit messages through to the Nessie committer yet.
   *   This is hacky but gets the job done until we can have a more complete commit/audit log.
   * </p>
   */
  private static String applicationId() {
    try {
      if (sparkConfMethod == null) {
        Class sparkEnvClazz = Class.forName("org.apache.spark.SparkEnv");
        sparkEnvMethod = sparkEnvClazz.getMethod("get");
        Class sparkConfClazz = Class.forName("org.apache.spark.SparkConf");
        sparkConfMethod = sparkEnvClazz.getMethod("conf");
        appIdMethod = sparkConfClazz.getMethod("getAppId");
      }
      Object sparkEnv = sparkEnvMethod.invoke(null);
      Object sparkConf = sparkConfMethod.invoke(sparkEnv);
      return "\nspark.app.id= " + appIdMethod.invoke(sparkConf);
    } catch (Exception e) {
      return "";
    }
  }

}
