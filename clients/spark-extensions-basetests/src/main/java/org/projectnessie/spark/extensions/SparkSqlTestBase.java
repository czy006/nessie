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
package org.projectnessie.spark.extensions;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.FormatMethod;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.internal.SQLConf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.client.http.HttpClientBuilder;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.ImmutableCommitMeta;
import org.projectnessie.model.ImmutableOperations;
import org.projectnessie.model.Operation;
import org.projectnessie.model.Operations;
import org.projectnessie.model.Reference;
import org.projectnessie.model.Tag;

public abstract class SparkSqlTestBase {

  protected static final int NESSIE_PORT = Integer.getInteger("quarkus.http.test-port", 19121);
  protected static final String NON_NESSIE_CATALOG = "invalid_hive";
  protected static SparkConf conf = new SparkConf();

  protected static SparkSession spark;
  protected static String url = String.format("http://localhost:%d/api/v1", NESSIE_PORT);

  protected Branch initialDefaultBranch;

  protected String refName;
  protected String additionalRefName;
  protected NessieApiV1 api;

  protected abstract String warehouseURI();

  protected Map<String, String> sparkHadoop() {
    return emptyMap();
  }

  protected Map<String, String> nessieParams() {
    return ImmutableMap.of("ref", defaultBranch(), "uri", url, "warehouse", warehouseURI());
  }

  @BeforeEach
  protected void setupSparkAndApi(TestInfo testInfo) throws NessieNotFoundException {
    api = HttpClientBuilder.builder().withUri(url).build(NessieApiV1.class);

    refName = testInfo.getTestMethod().map(Method::getName).get();
    additionalRefName = refName + "_other";

    initialDefaultBranch = api.getDefaultBranch();

    sparkHadoop().forEach((k, v) -> conf.set(String.format("spark.hadoop.%s", k), v));

    nessieParams()
        .forEach(
            (k, v) -> {
              conf.set(String.format("spark.sql.catalog.nessie.%s", k), v);
              conf.set(String.format("spark.sql.catalog.spark_catalog.%s", k), v);
            });

    conf.set(SQLConf.PARTITION_OVERWRITE_MODE().key(), "dynamic")
        .set("spark.testing", "true")
        .set("spark.sql.warehouse.dir", warehouseURI())
        .set("spark.sql.shuffle.partitions", "4")
        .set("spark.sql.catalog.nessie.catalog-impl", "org.apache.iceberg.nessie.NessieCatalog")
        .set("spark.sql.catalog.nessie", "org.apache.iceberg.spark.SparkCatalog");

    // the following catalog is only added to test a check in the nessie spark extensions
    conf.set(
            String.format("spark.sql.catalog.%s", NON_NESSIE_CATALOG),
            "org.apache.iceberg.spark.SparkCatalog")
        .set(
            String.format("spark.sql.catalog.%s.catalog-impl", NON_NESSIE_CATALOG),
            "org.apache.iceberg.hive.HiveCatalog");

    spark = SparkSession.builder().master("local[2]").config(conf).getOrCreate();
    spark.sparkContext().setLogLevel("WARN");
  }

  @AfterEach
  void removeBranches() throws NessieConflictException, NessieNotFoundException {
    try {
      // Reset potential "USE REFERENCE" statements from previous tests
      SparkSession.active()
          .sparkContext()
          .conf()
          .set(String.format("spark.sql.catalog.%s.ref", "nessie"), defaultBranch())
          .remove(String.format("spark.sql.catalog.%s.ref.hash", "nessie"));
    } catch (IllegalStateException e) {
      // Ignore potential java.lang.IllegalStateException: No active or default Spark session found
    }

    if (api != null) {
      Branch defaultBranch = api.getDefaultBranch();
      for (Reference ref : api.getAllReferences().get().getReferences()) {
        if (ref instanceof Branch && !ref.getName().equals(defaultBranch.getName())) {
          api.deleteBranch().branchName(ref.getName()).hash(ref.getHash()).delete();
        }
        if (ref instanceof Tag) {
          api.deleteTag().tagName(ref.getName()).hash(ref.getHash()).delete();
        }
      }
      api.assignBranch().assignTo(initialDefaultBranch).branch(defaultBranch).assign();
      api.close();
      api = null;
    }
  }

  @AfterAll
  static void tearDown() {
    if (spark != null) {
      spark.stop();
      spark = null;
    }
  }

  protected String defaultBranch() {
    return initialDefaultBranch.getName();
  }

  protected String defaultHash() {
    return initialDefaultBranch.getHash();
  }

  @FormatMethod
  protected static List<Object[]> sql(String query, Object... args) {
    List<Row> rows = spark.sql(String.format(query, args)).collectAsList();
    if (rows.size() < 1) {
      return ImmutableList.of();
    }

    return rows.stream().map(SparkSqlTestBase::toJava).collect(Collectors.toList());
  }

  @FormatMethod
  protected static List<Object[]> sqlWithEmptyCache(String query, Object... args) {
    try (SparkSession sparkWithEmptyCache = spark.cloneSession()) {
      List<Row> rows = sparkWithEmptyCache.sql(String.format(query, args)).collectAsList();
      return rows.stream().map(SparkSqlTestBase::toJava).collect(Collectors.toList());
    }
  }

  protected static Object[] toJava(Row row) {
    return IntStream.range(0, row.size())
        .mapToObj(
            pos -> {
              if (row.isNullAt(pos)) {
                return null;
              }

              Object value = row.get(pos);
              if (value instanceof Row) {
                return toJava((Row) value);
              } else if (value instanceof scala.collection.Seq) {
                return row.getList(pos);
              } else if (value instanceof scala.collection.Map) {
                return row.getJavaMap(pos);
              } else {
                return value;
              }
            })
        .toArray(Object[]::new);
  }

  /**
   * This looks weird but it gives a clear semantic way to turn a list of objects into a 'row' for
   * spark assertions.
   */
  protected static Object[] row(Object... values) {
    return values;
  }

  protected List<SparkCommitLogEntry> fetchLog(String branch) {
    return sql("SHOW LOG %s IN nessie", branch).stream()
        .map(SparkCommitLogEntry::fromShowLog)
        .collect(Collectors.toList());
  }

  protected void createBranchForTest(String branchName) throws NessieNotFoundException {
    assertThat(sql("CREATE BRANCH %s IN nessie", branchName))
        .containsExactly(row("Branch", branchName, defaultHash()));
    assertThat(api.getReference().refName(branchName).get())
        .isEqualTo(Branch.of(branchName, defaultHash()));
  }

  protected void createTagForTest(String tagName) throws NessieNotFoundException {
    assertThat(sql("CREATE TAG %s IN nessie", tagName))
        .containsExactly(row("Tag", tagName, defaultHash()));
    assertThat(api.getReference().refName(tagName).get()).isEqualTo(Tag.of(tagName, defaultHash()));
  }

  protected List<SparkCommitLogEntry> createBranchCommitAndReturnLog()
      throws NessieConflictException, NessieNotFoundException {
    createBranchForTest(refName);
    return commitAndReturnLog(refName, defaultHash());
  }

  protected List<SparkCommitLogEntry> commitAndReturnLog(String branch, String initalHashOrBranch)
      throws NessieNotFoundException, NessieConflictException {
    ContentKey key = ContentKey.of("table", "name");
    CommitMeta cm1 =
        ImmutableCommitMeta.builder()
            .author("sue")
            .authorTime(Instant.ofEpochMilli(1))
            .message("1")
            .putProperties("test", "123")
            .build();

    CommitMeta cm2 =
        ImmutableCommitMeta.builder()
            .author("janet")
            .authorTime(Instant.ofEpochMilli(10))
            .message("2")
            .putProperties("test", "123")
            .build();

    CommitMeta cm3 =
        ImmutableCommitMeta.builder()
            .author("alice")
            .authorTime(Instant.ofEpochMilli(100))
            .message("3")
            .putProperties("test", "123")
            .build();
    Operations ops =
        ImmutableOperations.builder()
            .addOperations(Operation.Put.of(key, IcebergTable.of("foo", 42, 42, 42, 42)))
            .commitMeta(cm1)
            .build();
    Operations ops2 =
        ImmutableOperations.builder()
            .addOperations(Operation.Put.of(key, IcebergTable.of("bar", 42, 42, 42, 42)))
            .commitMeta(cm2)
            .build();
    Operations ops3 =
        ImmutableOperations.builder()
            .addOperations(Operation.Put.of(key, IcebergTable.of("baz", 42, 42, 42, 42)))
            .commitMeta(cm3)
            .build();

    Branch ref1 =
        api.commitMultipleOperations()
            .branchName(branch)
            .hash(initalHashOrBranch)
            .operations(ops.getOperations())
            .commitMeta(ops.getCommitMeta())
            .commit();
    Branch ref2 =
        api.commitMultipleOperations()
            .branchName(branch)
            .hash(ref1.getHash())
            .operations(ops2.getOperations())
            .commitMeta(ops2.getCommitMeta())
            .commit();
    Branch ref3 =
        api.commitMultipleOperations()
            .branchName(branch)
            .hash(ref2.getHash())
            .operations(ops3.getOperations())
            .commitMeta(ops3.getCommitMeta())
            .commit();

    List<SparkCommitLogEntry> resultList = new ArrayList<>();
    resultList.add(SparkCommitLogEntry.fromCommitMeta(cm3, ref3.getHash()));
    resultList.add(SparkCommitLogEntry.fromCommitMeta(cm2, ref2.getHash()));
    resultList.add(SparkCommitLogEntry.fromCommitMeta(cm1, ref1.getHash()));
    return resultList;
  }
}
