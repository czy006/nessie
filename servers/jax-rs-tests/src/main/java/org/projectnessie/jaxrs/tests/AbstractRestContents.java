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
package org.projectnessie.jaxrs.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.groups.Tuple.tuple;

import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.projectnessie.client.api.CommitMultipleOperationsBuilder;
import org.projectnessie.client.ext.NessieApiVersion;
import org.projectnessie.client.ext.NessieApiVersions;
import org.projectnessie.error.BaseNessieClientServerException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.DiffResponse;
import org.projectnessie.model.DiffResponse.DiffEntry;
import org.projectnessie.model.EntriesResponse.Entry;
import org.projectnessie.model.FetchOption;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.IcebergView;
import org.projectnessie.model.ImmutableDeltaLakeTable;
import org.projectnessie.model.ImmutablePut;
import org.projectnessie.model.LogResponse;
import org.projectnessie.model.LogResponse.LogEntry;
import org.projectnessie.model.Operation;
import org.projectnessie.model.Operation.Delete;
import org.projectnessie.model.Operation.Put;
import org.projectnessie.model.Operation.Unchanged;

/** See {@link AbstractTestRest} for details about and reason for the inheritance model. */
public abstract class AbstractRestContents extends AbstractRestCommitLog {

  public static final class ContentAndOperationType {
    final Content.Type type;
    final Operation operation;
    final Put prepare;

    public ContentAndOperationType(Content.Type type, Operation operation) {
      this(type, operation, null);
    }

    public ContentAndOperationType(Content.Type type, Operation operation, Put prepare) {
      this.type = type;
      this.operation = operation;
      this.prepare = prepare;
    }

    @Override
    public String toString() {
      String s = opString(operation);
      return s + "_" + operation.getKey().toPathString();
    }

    private static String opString(Operation operation) {
      if (operation instanceof Put) {
        return "Put_" + ((Put) operation).getContent().getClass().getSimpleName();
      } else {
        return operation.getClass().getSimpleName();
      }
    }
  }

  public static Stream<ContentAndOperationType> contentAndOperationTypes() {
    return Stream.of(
        new ContentAndOperationType(
            Content.Type.ICEBERG_TABLE,
            Put.of(
                ContentKey.of("a", "iceberg"), IcebergTable.of("/iceberg/table", 42, 42, 42, 42))),
        new ContentAndOperationType(
            Content.Type.ICEBERG_VIEW,
            Put.of(
                ContentKey.of("a", "view"),
                IcebergView.of("/iceberg/view", 1, 1, "dial", "SELECT foo FROM table"))),
        new ContentAndOperationType(
            Content.Type.DELTA_LAKE_TABLE,
            Put.of(
                ContentKey.of("c", "delta"),
                ImmutableDeltaLakeTable.builder()
                    .addCheckpointLocationHistory("checkpoint")
                    .addMetadataLocationHistory("metadata")
                    .build())),
        new ContentAndOperationType(
            Content.Type.ICEBERG_TABLE,
            Delete.of(ContentKey.of("a", "iceberg_delete")),
            Put.of(
                ContentKey.of("a", "iceberg_delete"),
                IcebergTable.of("/iceberg/table", 42, 42, 42, 42))),
        new ContentAndOperationType(
            Content.Type.ICEBERG_TABLE,
            Unchanged.of(ContentKey.of("a", "iceberg_unchanged")),
            Put.of(
                ContentKey.of("a", "iceberg_unchanged"),
                IcebergTable.of("/iceberg/table", 42, 42, 42, 42))),
        new ContentAndOperationType(
            Content.Type.ICEBERG_VIEW,
            Delete.of(ContentKey.of("a", "view_delete")),
            Put.of(
                ContentKey.of("a", "view_delete"),
                IcebergView.of("/iceberg/view", 42, 42, "dial", "sql"))),
        new ContentAndOperationType(
            Content.Type.ICEBERG_VIEW,
            Unchanged.of(ContentKey.of("a", "view_unchanged")),
            Put.of(
                ContentKey.of("a", "view_unchanged"),
                IcebergView.of("/iceberg/view", 42, 42, "dial", "sql"))),
        new ContentAndOperationType(
            Content.Type.DELTA_LAKE_TABLE,
            Delete.of(ContentKey.of("a", "delta_delete")),
            Put.of(
                ContentKey.of("a", "delta_delete"),
                ImmutableDeltaLakeTable.builder()
                    .addMetadataLocationHistory("/delta/table")
                    .addCheckpointLocationHistory("/delta/history")
                    .lastCheckpoint("/delta/check")
                    .build())),
        new ContentAndOperationType(
            Content.Type.DELTA_LAKE_TABLE,
            Unchanged.of(ContentKey.of("a", "delta_unchanged")),
            Put.of(
                ContentKey.of("a", "delta_unchanged"),
                ImmutableDeltaLakeTable.builder()
                    .addMetadataLocationHistory("/delta/table")
                    .addCheckpointLocationHistory("/delta/history")
                    .lastCheckpoint("/delta/check")
                    .build())));
  }

  @Test
  public void verifyAllContentAndOperationTypes() throws BaseNessieClientServerException {
    Branch branch = createBranch("contentAndOperationAll");

    List<ContentAndOperationType> contentAndOps =
        contentAndOperationTypes().collect(Collectors.toList());

    CommitMultipleOperationsBuilder prepare =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("verifyAllContentAndOperationTypes prepare"));
    contentAndOps.stream()
        .filter(co -> co.prepare != null)
        .map(co -> co.prepare)
        .forEach(prepare::operation);
    branch = prepare.commit();

    CommitMultipleOperationsBuilder commit =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("verifyAllContentAndOperationTypes"));
    contentAndOps.forEach(contentAndOp -> commit.operation(contentAndOp.operation));
    Branch committed = commit.commit();

    List<Entry> entries =
        getApi().getEntries().refName(branch.getName()).stream().collect(Collectors.toList());
    List<Entry> expect =
        contentAndOps.stream()
            .filter(c -> c.operation instanceof Put)
            .map(c -> Entry.entry(c.operation.getKey(), c.type))
            .collect(Collectors.toList());
    List<Entry> notExpect =
        contentAndOps.stream()
            .filter(c -> c.operation instanceof Delete)
            .map(c -> Entry.entry(c.operation.getKey(), c.type))
            .collect(Collectors.toList());
    soft.assertThat(entries).containsAll(expect).doesNotContainAnyElementsOf(notExpect);

    // Diff against of committed HEAD and previous commit must yield the content in the
    // Put operations
    soft.assertThat(getApi().getDiff().fromRef(committed).toRef(branch).get())
        .extracting(DiffResponse::getDiffs, list(DiffEntry.class))
        .filteredOn(e -> e.getFrom() != null)
        .extracting(AbstractRest::diffEntryWithoutContentId)
        .containsExactlyInAnyOrderElementsOf(
            contentAndOps.stream()
                .map(c -> c.operation)
                .filter(op -> op instanceof Put)
                .map(Put.class::cast)
                .map(put -> DiffEntry.diffEntry(put.getKey(), put.getContent()))
                .collect(Collectors.toList()));

    // Verify that 'get contents' for the HEAD commit returns exactly the committed contents
    List<ContentKey> allKeys =
        contentAndOps.stream()
            .map(contentAndOperationType -> contentAndOperationType.operation.getKey())
            .collect(Collectors.toList());
    Map<ContentKey, Content> expected =
        contentAndOps.stream()
            .map(
                c -> {
                  if (c.operation instanceof Put) {
                    return Maps.immutableEntry(
                        c.operation.getKey(), ((Put) c.operation).getContent());
                  }
                  if (c.operation instanceof Unchanged) {
                    return Maps.immutableEntry(
                        c.operation.getKey(), ((Put) c.prepare).getContent());
                  }
                  return null;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    soft.assertThat(getApi().getContent().reference(committed).keys(allKeys).get())
        .containsOnlyKeys(expected.keySet())
        .allSatisfy(
            (key, content) -> assertThat(clearIdOnContent(content)).isEqualTo(expected.get(key)));

    // Verify that the operations on the HEAD commit contains the committed operations
    soft.assertThat(getApi().getCommitLog().reference(committed).fetch(FetchOption.ALL).stream())
        .element(0)
        .extracting(LogEntry::getOperations)
        .extracting(this::clearIdOnOperations, list(Operation.class))
        .containsExactlyInAnyOrderElementsOf(
            contentAndOps.stream()
                .map(c -> c.operation)
                .filter(op -> !(op instanceof Unchanged))
                .collect(Collectors.toList()));
  }

  @ParameterizedTest
  @MethodSource("contentAndOperationTypes")
  public void verifyContentAndOperationTypesIndividually(
      ContentAndOperationType contentAndOperationType) throws BaseNessieClientServerException {
    Branch branch = createBranch("contentAndOperation_" + contentAndOperationType);

    if (contentAndOperationType.prepare != null) {
      branch =
          getApi()
              .commitMultipleOperations()
              .branch(branch)
              .commitMeta(CommitMeta.fromMessage("verifyAllContentAndOperationTypes prepare"))
              .operation(contentAndOperationType.prepare)
              .commit();
    }

    CommitMultipleOperationsBuilder commit =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("commit " + contentAndOperationType))
            .operation(contentAndOperationType.operation);
    Branch committed = commit.commit();

    // Oh, yea - this is weird. The property ContentAndOperationType.operation.key.namespace is null
    // (!!!) here, because somehow JUnit @MethodSource implementation re-constructs the objects
    // returned from the source-method contentAndOperationTypes.
    ContentKey fixedContentKey =
        ContentKey.of(contentAndOperationType.operation.getKey().getElements());

    if (contentAndOperationType.operation instanceof Put) {
      Put put = (Put) contentAndOperationType.operation;

      List<Entry> entries =
          getApi().getEntries().refName(branch.getName()).stream().collect(Collectors.toList());
      soft.assertThat(entries)
          .containsExactly(Entry.entry(fixedContentKey, contentAndOperationType.type));

      // Diff against of committed HEAD and previous commit must yield the content in the
      // Put operation
      soft.assertThat(getApi().getDiff().fromRef(committed).toRef(branch).get())
          .extracting(DiffResponse::getDiffs, list(DiffEntry.class))
          .extracting(DiffEntry::getKey, e -> clearIdOnContent(e.getFrom()), DiffEntry::getTo)
          .containsExactly(tuple(fixedContentKey, put.getContent(), null));

      // Compare content on HEAD commit with the committed content
      Map<ContentKey, Content> content =
          getApi().getContent().key(fixedContentKey).reference(committed).get();
      soft.assertThat(content)
          .extractingByKey(fixedContentKey)
          .extracting(this::clearIdOnContent)
          .isEqualTo(put.getContent());

      // Compare operation on HEAD commit with the committed operation
      List<LogResponse.LogEntry> log =
          getApi().getCommitLog().reference(committed).fetch(FetchOption.ALL).stream()
              .collect(Collectors.toList());
      soft.assertThat(log)
          .element(0)
          .extracting(LogEntry::getOperations, list(Operation.class))
          .element(0)
          // Clear content ID for comparison
          .extracting(this::clearIdOnOperation)
          .isEqualTo(put);
    } else if (contentAndOperationType.operation instanceof Delete) {
      List<Entry> entries =
          getApi().getEntries().refName(branch.getName()).stream().collect(Collectors.toList());
      soft.assertThat(entries).isEmpty();

      // Diff against of committed HEAD and previous commit must yield the content in the
      // Put operations
      soft.assertThat(getApi().getDiff().fromRef(committed).toRef(branch).get())
          .extracting(DiffResponse::getDiffs, list(DiffEntry.class))
          .filteredOn(e -> e.getFrom() != null)
          .isEmpty();

      // Compare content on HEAD commit with the committed content
      Map<ContentKey, Content> content =
          getApi().getContent().key(fixedContentKey).reference(committed).get();
      soft.assertThat(content).isEmpty();

      // Compare operation on HEAD commit with the committed operation
      List<LogResponse.LogEntry> log =
          getApi().getCommitLog().reference(committed).fetch(FetchOption.ALL).stream()
              .collect(Collectors.toList());
      soft.assertThat(log)
          .element(0)
          .extracting(LogEntry::getOperations, list(Operation.class))
          .containsExactly(contentAndOperationType.operation);
    } else if (contentAndOperationType.operation instanceof Unchanged) {
      List<Entry> entries =
          getApi().getEntries().refName(branch.getName()).stream().collect(Collectors.toList());
      soft.assertThat(entries)
          .containsExactly(Entry.entry(fixedContentKey, contentAndOperationType.type));

      // Diff against of committed HEAD and previous commit must yield the content in the
      // Put operations
      soft.assertThat(getApi().getDiff().fromRef(committed).toRef(branch).get())
          .extracting(DiffResponse::getDiffs, list(DiffEntry.class))
          .filteredOn(e -> e.getFrom() != null)
          .isEmpty();

      // Compare content on HEAD commit with the committed content
      Map<ContentKey, Content> content =
          getApi().getContent().key(fixedContentKey).reference(committed).get();
      soft.assertThat(content)
          .extractingByKey(fixedContentKey)
          .extracting(this::clearIdOnContent)
          .isEqualTo(contentAndOperationType.prepare.getContent());

      // Compare operation on HEAD commit with the committed operation
      List<LogResponse.LogEntry> log =
          getApi().getCommitLog().reference(committed).fetch(FetchOption.ALL).stream()
              .collect(Collectors.toList());
      soft.assertThat(log).element(0).extracting(LogEntry::getOperations).isNull();
    }
  }

  private List<Operation> clearIdOnOperations(List<Operation> o) {
    return o.stream().map(this::clearIdOnOperation).collect(Collectors.toList());
  }

  private Operation clearIdOnOperation(Operation o) {
    try {
      if (!(o instanceof Put)) {
        return o;
      }
      Put put = (Put) o;
      Content contentWithoutId = clearIdOnContent(put.getContent());
      return ImmutablePut.builder().from(put).content(contentWithoutId).build();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Content clearIdOnContent(Content content) {
    return setIdOnContent(content, null);
  }

  private Content setIdOnContent(Content content, String contentId) {
    try {
      return (Content)
          content
              .getClass()
              .getDeclaredMethod("withId", String.class)
              .invoke(content, new Object[] {contentId});
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void multiget() throws BaseNessieClientServerException {
    Branch branch = createBranch("foo");
    ContentKey keyA = ContentKey.of("a");
    ContentKey keyB = ContentKey.of("b");
    IcebergTable tableA = IcebergTable.of("path1", 42, 42, 42, 42);
    IcebergTable tableB = IcebergTable.of("path2", 42, 42, 42, 42);
    getApi()
        .commitMultipleOperations()
        .branch(branch)
        .operation(Put.of(keyA, tableA))
        .commitMeta(CommitMeta.fromMessage("commit 1"))
        .commit();
    getApi()
        .commitMultipleOperations()
        .branch(branch)
        .operation(Put.of(keyB, tableB))
        .commitMeta(CommitMeta.fromMessage("commit 2"))
        .commit();
    Map<ContentKey, Content> response =
        getApi()
            .getContent()
            .key(keyA)
            .key(keyB)
            .key(ContentKey.of("noexist"))
            .refName("foo")
            .get();
    assertThat(response)
        .containsKeys(keyA, keyB)
        .hasEntrySatisfying(
            keyA,
            content ->
                assertThat(content)
                    .isEqualTo(IcebergTable.builder().from(tableA).id(content.getId()).build()))
        .hasEntrySatisfying(
            keyB,
            content ->
                assertThat(content)
                    .isEqualTo(IcebergTable.builder().from(tableB).id(content.getId()).build()))
        .doesNotContainKey(ContentKey.of("noexist"));
  }

  @Test
  @NessieApiVersions(versions = NessieApiVersion.V2)
  public void fetchContentByNamelessReference() throws BaseNessieClientServerException {
    Branch branch = createBranch("fetchContentByNamelessReference");
    IcebergTable t = IcebergTable.of("loc", 1, 2, 3, 4);
    ContentKey key = ContentKey.of("key1");
    CommitMultipleOperationsBuilder commit =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("test commit"))
            .operation(Put.of(key, t));
    Branch committed = commit.commit();

    assertThat(getApi().getContent().hashOnRef(committed.getHash()).key(key).get().get(key))
        .isInstanceOf(IcebergTable.class);
  }
}
