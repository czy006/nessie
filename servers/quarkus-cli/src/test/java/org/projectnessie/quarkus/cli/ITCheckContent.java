/*
 * Copyright (C) 2022 Dremio
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
package org.projectnessie.quarkus.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.projectnessie.versioned.store.DefaultStoreWorker.payloadForContent;
import static org.projectnessie.versioned.testworker.OnRefOnly.onRef;

import com.google.protobuf.ByteString;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.versioned.BranchName;
import org.projectnessie.versioned.GetNamedRefsParams;
import org.projectnessie.versioned.Key;
import org.projectnessie.versioned.ReferenceInfo;
import org.projectnessie.versioned.persist.adapter.ContentId;
import org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import org.projectnessie.versioned.persist.adapter.ImmutableCommitParams;
import org.projectnessie.versioned.persist.adapter.KeyWithBytes;
import org.projectnessie.versioned.testworker.OnRefOnly;

@QuarkusMainTest
@TestProfile(QuarkusCliTestProfileMongo.class)
@ExtendWith(NessieCliTestExtension.class)
class ITCheckContent extends BaseContentTest<CheckContentEntry> {

  private static final IcebergTable table1 = IcebergTable.of("meta_111", 1, 2, 3, 4, "111");
  private static final IcebergTable table2 = IcebergTable.of("meta_222", 2, 3, 4, 5, "222");
  private static final IcebergTable table3 = IcebergTable.of("meta_333", 3, 4, 5, 6, "333");
  private static final IcebergTable table4 = IcebergTable.of("meta_444", 4, 5, 6, 7, "444");

  ITCheckContent() {
    super(CheckContentEntry.class);
  }

  @Test
  public void testEmptyRepo(QuarkusMainLauncher launcher) {
    launchNoFile(launcher, "check-content");
    assertThat(result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testNonExistingKey(QuarkusMainLauncher launcher) throws Exception {
    launch(launcher, "check-content", "-k", "namespace123", "-k", "unknown12345");
    assertThat(entries).hasSize(1);
    assertThat(entries)
        .first()
        .satisfies(
            e -> {
              assertThat(e.getStatus()).isEqualTo("ERROR");
              assertThat(e.getKey()).isEqualTo(ContentKey.of("namespace123", "unknown12345"));
              assertThat(e.getContent()).isNull();
              assertThat(e.getErrorMessage()).isEqualTo("Missing content");
            });
    assertThat(result.exitCode()).isEqualTo(2);
  }

  @Test
  public void testAdapterError(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {
    Key k1 = Key.of("namespace123", "table123");
    adapter.commit(
        ImmutableCommitParams.builder()
            .toBranch(BranchName.of("main"))
            .commitMetaSerialized(ByteString.copyFrom(new byte[] {1, 2, 3}))
            .addPuts(
                KeyWithBytes.of(
                    k1,
                    ContentId.of("id123"),
                    payloadForContent(Content.Type.ICEBERG_TABLE),
                    ByteString.copyFrom(new byte[] {1, 2, 3})))
            .build());

    launch(launcher, "check-content");
    assertThat(entries).hasSize(1);
    assertThat(entries)
        .first()
        .satisfies(
            e -> {
              assertThat(e.getStatus()).isEqualTo("ERROR");
              assertThat(e.getKey()).isEqualTo(ContentKey.of("namespace123", "table123"));
              assertThat(e.getContent()).isNull();
              assertThat(e.getErrorMessage()).isEqualTo("Failure parsing data");
              assertThat(e.getExceptionStackTrace())
                  .contains("Protocol message contained an invalid tag");
            });
    assertThat(result.exitCode()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 10})
  public void testWorkerError(int batchSize, QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {

    ByteString broken = ByteString.copyFrom(new byte[] {1, 2, 3});
    commit(table1, adapter, broken);
    commit(table2, adapter, broken);
    commit(table3, adapter, broken);
    commit(table4, adapter, broken);

    // Note: SimpleStoreWorker will not be able to parse IcebergTable objects
    launch(launcher, "check-content", "--summary", "--batch=" + batchSize);
    assertThat(entries).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo("ERROR"));
    assertThat(entries).allSatisfy(e -> assertThat(e.getErrorMessage()).isNotEmpty());
    assertThat(entries).allSatisfy(e -> assertThat(e.getExceptionStackTrace()).isNotEmpty());
    assertThat(entries).hasSize(4);
    assertThat(entries).anySatisfy(e -> assertThat(e.getKey().getName()).isEqualTo("table_111"));
    assertThat(entries).anySatisfy(e -> assertThat(e.getKey().getName()).isEqualTo("table_222"));
    assertThat(entries).anySatisfy(e -> assertThat(e.getKey().getName()).isEqualTo("table_333"));
    assertThat(entries).anySatisfy(e -> assertThat(e.getKey().getName()).isEqualTo("table_444"));
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.getOutputStream()).contains("Detected 4 errors in 4 keys.");
  }

  @Test
  public void testValidData(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {

    commit(table1, adapter);
    commit(table2, adapter);

    launch(launcher, "check-content", "--show-content");
    assertThat(entries).hasSize(2);
    assertThat(entries).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo("OK"));
    assertThat(entries)
        .anySatisfy(
            e -> {
              assertThat(e.getKey()).isEqualTo(ContentKey.of("test_namespace", "table_111"));
              assertThat(e.getContent()).isEqualTo(table1);
            });
    assertThat(entries)
        .anySatisfy(
            e -> {
              assertThat(e.getKey()).isEqualTo(ContentKey.of("test_namespace", "table_222"));
              assertThat(e.getContent()).isEqualTo(table2);
            });
    assertThat(result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testValidDataNoContent(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {
    commit(table1, adapter);
    commit(table2, adapter);

    launch(launcher, "check-content");
    assertThat(entries).hasSize(2);
    assertThat(entries).allSatisfy(e -> assertThat(e.getContent()).isNull());
    assertThat(result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testValidDataStdOut(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {
    commit(table1, adapter);
    commit(table2, adapter);

    launchNoFile(
        launcher, "check-content", "-s", "--show-content", "--output", "-"); // '-' for STDOUT
    assertThat(result.getOutputStream()).anySatisfy(line -> assertThat(line).contains("table_111"));
    assertThat(result.getOutputStream()).anySatisfy(line -> assertThat(line).contains("table_222"));
    assertThat(result.getOutputStream()).anySatisfy(line -> assertThat(line).contains("meta_111"));
    assertThat(result.getOutputStream()).anySatisfy(line -> assertThat(line).contains("meta_222"));
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.getOutputStream()).contains("Detected 0 errors in 2 keys.");
  }

  @Test
  public void testErrorOnly(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {
    commit(table1, adapter);
    launch(launcher, "check-content", "--error-only");
    assertThat(entries).hasSize(0);
    assertThat(result.exitCode()).isEqualTo(0);
  }

  @Test
  public void testHashWithBrokenCommit(QuarkusMainLauncher launcher, DatabaseAdapter adapter)
      throws Exception {
    commit(table1, adapter);
    ReferenceInfo good = adapter.namedRef("main", GetNamedRefsParams.DEFAULT);

    OnRefOnly val = onRef("123", "222");
    commit(val.getId(), payloadForContent(val), val.serialized(), adapter);

    launch(launcher, "check-content", "--hash", good.getHash().asString());
    assertThat(entries).hasSize(1);
    assertThat(entries).allSatisfy(e -> assertThat(e.getKey().getName()).isEqualTo("table_111"));
    assertThat(entries).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo("OK"));
    assertThat(result.exitCode()).isEqualTo(0);
  }
}
