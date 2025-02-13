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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.iterable.ThrowingExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.client.api.CommitMultipleOperationsBuilder;
import org.projectnessie.error.BaseNessieClientServerException;
import org.projectnessie.error.NessieNamespaceAlreadyExistsException;
import org.projectnessie.error.NessieNamespaceNotEmptyException;
import org.projectnessie.error.NessieNamespaceNotFoundException;
import org.projectnessie.error.NessieReferenceConflictException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.EntriesResponse.Entry;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.LogResponse;
import org.projectnessie.model.LogResponse.LogEntry;
import org.projectnessie.model.Namespace;
import org.projectnessie.model.Operation.Put;

/** See {@link AbstractTestRest} for details about and reason for the inheritance model. */
public abstract class AbstractRestNamespace extends AbstractRestRefLog {

  @ParameterizedTest
  @ValueSource(strings = {"a.b.c", "a.b\u001Dc.d", "a.b.c.d", "a.b\u0000c.d"})
  public void testNamespaces(String namespaceName) throws BaseNessieClientServerException {
    Branch branch = createBranch("testNamespaces");
    Namespace ns = Namespace.parse(namespaceName);
    Namespace namespace =
        getApi().createNamespace().refName(branch.getName()).namespace(ns).create();

    soft.assertThat(namespace)
        .isNotNull()
        .extracting(Namespace::getElements, Namespace::toPathString)
        .containsExactly(ns.getElements(), ns.toPathString());

    Namespace got = getApi().getNamespace().refName(branch.getName()).namespace(ns).get();
    soft.assertThat(got).isEqualTo(namespace);

    // the namespace in the error message will contain the representation with u001D
    String namespaceInErrorMsg = namespaceName.replace("\u0000", "\u001D");

    soft.assertThatThrownBy(
            () -> getApi().createNamespace().refName(branch.getName()).namespace(ns).create())
        .isInstanceOf(NessieNamespaceAlreadyExistsException.class)
        .hasMessage(String.format("Namespace '%s' already exists", namespaceInErrorMsg));

    getApi().deleteNamespace().refName(branch.getName()).namespace(ns).delete();
    soft.assertThatThrownBy(
            () -> getApi().deleteNamespace().refName(branch.getName()).namespace(ns).delete())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage(String.format("Namespace '%s' does not exist", namespaceInErrorMsg));

    soft.assertThatThrownBy(
            () -> getApi().getNamespace().refName(branch.getName()).namespace(ns).get())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage(String.format("Namespace '%s' does not exist", namespaceInErrorMsg));

    soft.assertThatThrownBy(
            () ->
                getApi()
                    .deleteNamespace()
                    .refName(branch.getName())
                    .namespace(Namespace.parse("nonexisting"))
                    .delete())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage("Namespace 'nonexisting' does not exist");
  }

  @Test
  public void testNamespacesRetrieval() throws BaseNessieClientServerException {
    Branch branch = createBranch("namespace");

    ThrowingExtractor<String, Namespace, ?> createNamespace =
        identifier ->
            getApi()
                .createNamespace()
                .refName(branch.getName())
                .namespace(Namespace.parse(identifier))
                .create();

    Namespace one = createNamespace.apply("a.b.c");
    Namespace two = createNamespace.apply("a.b.d");
    Namespace three = createNamespace.apply("x.y.z");
    Namespace four = createNamespace.apply("one.two");
    for (Namespace namespace : Arrays.asList(one, two, three, four)) {
      soft.assertThat(namespace).isNotNull();
      soft.assertThat(namespace.getId()).isNotNull();
    }

    soft.assertThat(
            getApi().getMultipleNamespaces().refName(branch.getName()).get().getNamespaces())
        .containsExactlyInAnyOrder(one, two, three, four);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace(Namespace.EMPTY)
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrder(one, two, three, four);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("a")
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrder(one, two);
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("a.b")
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrder(one, two);
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("a.b.c")
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrder(one);
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("a.b.d")
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrder(two);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("x")
                .get()
                .getNamespaces())
        .containsExactly(three);
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("z")
                .get()
                .getNamespaces())
        .isEmpty();
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace("one")
                .get()
                .getNamespaces())
        .containsExactly(four);
  }

  @Test
  public void testNamespaceDeletion() throws BaseNessieClientServerException {
    Branch init = createBranch("testNamespaceDeletion");

    List<ContentAndOperationType> contentAndOps =
        contentAndOperationTypes().collect(Collectors.toList());

    CommitMultipleOperationsBuilder prepare =
        getApi()
            .commitMultipleOperations()
            .branch(init)
            .commitMeta(CommitMeta.fromMessage("verifyAllContentAndOperationTypes prepare"));
    contentAndOps.stream()
        .filter(co -> co.prepare != null)
        .map(co -> co.prepare)
        .forEach(prepare::operation);
    Branch branch = prepare.commit();

    CommitMultipleOperationsBuilder commit =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("verifyAllContentAndOperationTypes"));
    contentAndOps.stream().map(c -> c.operation).forEach(commit::operation);
    commit.commit();

    List<Entry> entries =
        contentAndOps.stream()
            .filter(c -> c.operation instanceof Put)
            .map(c -> Entry.entry(c.operation.getKey(), c.type))
            .collect(Collectors.toList());

    CommitMultipleOperationsBuilder commit2 =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("create namespaces"));
    entries.stream()
        .map(e -> e.getName().getNamespace())
        .distinct()
        .forEach(
            ns -> {
              commit2.operation(Put.of(ContentKey.of(ns.getElements()), ns));
            });
    commit2.commit();

    for (Entry e : entries) {
      Namespace namespace = e.getName().getNamespace();
      soft.assertThat(
              getApi()
                  .getNamespace()
                  .refName(branch.getName())
                  .namespace(namespace)
                  .get()
                  .getElements())
          .isEqualTo(namespace.getElements());

      soft.assertThatThrownBy(
              () ->
                  getApi()
                      .deleteNamespace()
                      .refName(branch.getName())
                      .namespace(namespace)
                      .delete())
          .isInstanceOf(NessieNamespaceNotEmptyException.class)
          .hasMessage(String.format("Namespace '%s' is not empty", namespace));
    }
  }

  @Test
  public void testNamespaceMerge() throws BaseNessieClientServerException {
    Branch base = createBranch("merge-base");
    base =
        getApi()
            .commitMultipleOperations()
            .branch(base)
            .commitMeta(CommitMeta.fromMessage("root"))
            .operation(Put.of(ContentKey.of("root"), IcebergTable.of("/dev/null", 42, 42, 42, 42)))
            .commit();

    Branch branch = createBranch("merge-branch", base);
    Namespace ns = Namespace.parse("a.b.c");
    // create the same namespace on both branches
    getApi().createNamespace().namespace(ns).refName(branch.getName()).create();

    base = (Branch) getApi().getReference().refName(base.getName()).get();
    branch = (Branch) getApi().getReference().refName(branch.getName()).get();
    getApi().mergeRefIntoBranch().branch(base).fromRef(branch).merge();

    LogResponse log =
        getApi().getCommitLog().refName(base.getName()).untilHash(base.getHash()).get();
    String expectedCommitMsg = "create namespace a.b.c";
    soft.assertThat(
            log.getLogEntries().stream().map(LogEntry::getCommitMeta).map(CommitMeta::getMessage))
        .containsExactly(expectedCommitMsg, "root");

    soft.assertThat(
            getApi().getEntries().refName(base.getName()).get().getEntries().stream()
                .map(Entry::getName))
        .contains(ContentKey.of(ns.getElements()));

    soft.assertThat(getApi().getNamespace().refName(base.getName()).namespace(ns).get())
        .isNotNull();
  }

  @Test
  public void testNamespaceMergeWithConflict() throws BaseNessieClientServerException {
    Branch base = createBranch("merge-base");
    base =
        getApi()
            .commitMultipleOperations()
            .branch(base)
            .commitMeta(CommitMeta.fromMessage("root"))
            .operation(Put.of(ContentKey.of("root"), IcebergTable.of("/dev/null", 42, 42, 42, 42)))
            .commit();

    Branch branch = createBranch("merge-branch", base);
    Namespace ns = Namespace.parse("a.b.c");
    // create a namespace on the base branch
    getApi().createNamespace().namespace(ns).refName(base.getName()).create();
    base = (Branch) getApi().getReference().refName(base.getName()).get();

    // create a table with the same name on the other branch
    IcebergTable table = IcebergTable.of("merge-table1", 42, 42, 42, 42);
    branch =
        getApi()
            .commitMultipleOperations()
            .branch(branch)
            .commitMeta(CommitMeta.fromMessage("test-merge-branch1"))
            .operation(Put.of(ContentKey.of("a", "b", "c"), table))
            .commit();
    Branch finalBase = base;
    Branch finalBranch = branch;
    soft.assertThatThrownBy(
            () -> getApi().mergeRefIntoBranch().branch(finalBase).fromRef(finalBranch).merge())
        .isInstanceOf(NessieReferenceConflictException.class)
        .hasMessage("The following keys have been changed in conflict: 'a.b.c'");

    LogResponse log =
        getApi().getCommitLog().refName(base.getName()).untilHash(base.getHash()).get();
    // merging should not have been possible ("test-merge-branch1" shouldn't be in the commits)
    soft.assertThat(
            log.getLogEntries().stream().map(LogEntry::getCommitMeta).map(CommitMeta::getMessage))
        .containsExactly("create namespace a.b.c");

    List<Entry> entries = getApi().getEntries().refName(base.getName()).get().getEntries();
    soft.assertThat(entries.stream().map(Entry::getName)).contains(ContentKey.of(ns.getElements()));

    soft.assertThat(getApi().getNamespace().refName(base.getName()).namespace(ns).get())
        .isNotNull();
  }

  @Test
  public void testNamespaceConflictWithOtherContent() throws BaseNessieClientServerException {
    Branch branch = createBranch("testNamespaceConflictWithOtherContent");
    IcebergTable icebergTable = IcebergTable.of("icebergTable", 42, 42, 42, 42);

    List<String> elements = Arrays.asList("a", "b", "c");
    ContentKey key = ContentKey.of(elements);
    getApi()
        .commitMultipleOperations()
        .branchName(branch.getName())
        .hash(branch.getHash())
        .commitMeta(CommitMeta.fromMessage("add table"))
        .operation(Put.of(key, icebergTable))
        .commit();

    Namespace ns = Namespace.of(elements);
    soft.assertThatThrownBy(
            () -> getApi().createNamespace().refName(branch.getName()).namespace(ns).create())
        .isInstanceOf(NessieNamespaceAlreadyExistsException.class)
        .hasMessage("Another content object with name 'a.b.c' already exists");

    soft.assertThatThrownBy(
            () -> getApi().getNamespace().refName(branch.getName()).namespace(ns).get())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage("Namespace 'a.b.c' does not exist");

    soft.assertThatThrownBy(
            () -> getApi().deleteNamespace().refName(branch.getName()).namespace(ns).delete())
        .isInstanceOf(NessieNamespaceNotFoundException.class);
  }

  @Test
  public void testNamespacesWithAndWithoutZeroBytes() throws BaseNessieClientServerException {
    Branch branch = createBranch("testNamespacesWithAndWithoutZeroBytes");
    String firstName = "a.b\u0000c.d";
    String secondName = "a.b.c.d";

    // perform creation and retrieval
    ThrowingExtractor<String, Namespace, ?> creator =
        identifier -> {
          Namespace namespace = Namespace.parse(identifier);

          Namespace created =
              getApi().createNamespace().refName(branch.getName()).namespace(namespace).create();
          soft.assertThat(created)
              .isNotNull()
              .extracting(Namespace::getElements, Namespace::toPathString)
              .containsExactly(namespace.getElements(), namespace.toPathString());

          soft.assertThat(
                  getApi().getNamespace().refName(branch.getName()).namespace(namespace).get())
              .isEqualTo(created);

          soft.assertThatThrownBy(
                  () ->
                      getApi()
                          .createNamespace()
                          .refName(branch.getName())
                          .namespace(namespace)
                          .create())
              .isInstanceOf(NessieNamespaceAlreadyExistsException.class)
              .hasMessage(String.format("Namespace '%s' already exists", namespace.name()));

          soft.assertAll();

          return created;
        };

    Namespace first = creator.apply(firstName);
    Namespace second = creator.apply(secondName);
    List<Namespace> namespaces = Arrays.asList(first, second);

    // retrieval by prefix
    soft.assertThat(
            getApi().getMultipleNamespaces().refName(branch.getName()).get().getNamespaces())
        .containsExactlyInAnyOrderElementsOf(namespaces);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .namespace("a")
                .refName(branch.getName())
                .get()
                .getNamespaces())
        .containsExactlyInAnyOrderElementsOf(namespaces);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .namespace("a.b")
                .refName(branch.getName())
                .get()
                .getNamespaces())
        .containsExactly(second);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .namespace("a.b\u001Dc")
                .refName(branch.getName())
                .get()
                .getNamespaces())
        .containsExactly(first);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .namespace("a.b\u0000c")
                .refName(branch.getName())
                .get()
                .getNamespaces())
        .containsExactly(first);

    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .namespace("a.b.c")
                .refName(branch.getName())
                .get()
                .getNamespaces())
        .containsExactly(second);

    // deletion
    for (Namespace namespace : namespaces) {
      getApi().deleteNamespace().refName(branch.getName()).namespace(namespace).delete();

      soft.assertThatThrownBy(
              () ->
                  getApi()
                      .deleteNamespace()
                      .refName(branch.getName())
                      .namespace(namespace)
                      .delete())
          .isInstanceOf(NessieNamespaceNotFoundException.class)
          .hasMessage(String.format("Namespace '%s' does not exist", namespace.name()));
    }

    soft.assertThat(
            getApi().getMultipleNamespaces().refName(branch.getName()).get().getNamespaces())
        .isEmpty();
  }

  @Test
  public void testEmptyNamespace() throws BaseNessieClientServerException {
    Branch branch = createBranch("emptyNamespace");
    // can't create/fetch/delete an empty namespace due to empty REST path
    soft.assertThatThrownBy(
            () ->
                getApi()
                    .createNamespace()
                    .refName(branch.getName())
                    .namespace(Namespace.EMPTY)
                    .create())
        .isInstanceOf(Exception.class);

    soft.assertThatThrownBy(
            () ->
                getApi().getNamespace().refName(branch.getName()).namespace(Namespace.EMPTY).get())
        .isInstanceOf(Exception.class);

    soft.assertThatThrownBy(
            () ->
                getApi()
                    .deleteNamespace()
                    .refName(branch.getName())
                    .namespace(Namespace.EMPTY)
                    .delete())
        .isInstanceOf(Exception.class);

    soft.assertThat(
            getApi().getMultipleNamespaces().refName(branch.getName()).get().getNamespaces())
        .isEmpty();

    ContentKey keyWithoutNamespace = ContentKey.of("icebergTable");
    getApi()
        .commitMultipleOperations()
        .branchName(branch.getName())
        .hash(branch.getHash())
        .commitMeta(CommitMeta.fromMessage("add table"))
        .operation(Put.of(keyWithoutNamespace, IcebergTable.of("icebergTable", 42, 42, 42, 42)))
        .commit();

    soft.assertThat(
            getApi().getMultipleNamespaces().refName(branch.getName()).get().getNamespaces())
        .isEmpty();
    soft.assertThat(
            getApi()
                .getMultipleNamespaces()
                .refName(branch.getName())
                .namespace(Namespace.EMPTY)
                .get()
                .getNamespaces())
        .isEmpty();
  }

  @Test
  public void testNamespaceWithProperties() throws BaseNessieClientServerException {
    Branch branch = createBranch("namespaceWithProperties");
    Map<String, String> properties = ImmutableMap.of("key1", "val1", "key2", "val2");
    Namespace namespace = Namespace.of(properties, "a", "b", "c");

    Namespace ns =
        getApi()
            .createNamespace()
            .namespace(namespace)
            .properties(properties)
            .reference(branch)
            .create();
    soft.assertThat(ns.getProperties()).isEqualTo(properties);
    soft.assertThat(ns.getId()).isNotNull();
    String nsId = ns.getId();

    soft.assertThatThrownBy(
            () ->
                getApi()
                    .updateProperties()
                    .reference(branch)
                    .namespace("non-existing")
                    .updateProperties(properties)
                    .update())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage("Namespace 'non-existing' does not exist");

    // Re-run with invalid name, but different parameters to ensure that missing parameters do not
    // fail the request before the name is validated.
    soft.assertThatThrownBy(
            () ->
                getApi()
                    .updateProperties()
                    .reference(branch)
                    .namespace("non-existing")
                    .removeProperties(properties.keySet())
                    .update())
        .isInstanceOf(NessieNamespaceNotFoundException.class)
        .hasMessage("Namespace 'non-existing' does not exist");

    getApi()
        .updateProperties()
        .refName(branch.getName())
        .namespace(namespace)
        .updateProperties(properties)
        .update();

    // namespace does not exist at the previous hash
    soft.assertThatThrownBy(
            () -> getApi().getNamespace().reference(branch).namespace(namespace).get())
        .isInstanceOf(NessieNamespaceNotFoundException.class);

    Branch updated = (Branch) getApi().getReference().refName(branch.getName()).get();
    ns = getApi().getNamespace().reference(updated).namespace(namespace).get();
    soft.assertThat(ns.getProperties()).isEqualTo(properties);
    soft.assertThat(ns.getId()).isEqualTo(nsId);

    getApi()
        .updateProperties()
        .reference(updated)
        .namespace(namespace)
        .updateProperties(ImmutableMap.of("key3", "val3", "key1", "xyz"))
        .removeProperties(ImmutableSet.of("key2", "key5"))
        .update();

    // "updated" still points to the hash prior to the update
    soft.assertThat(
            getApi().getNamespace().reference(updated).namespace(namespace).get().getProperties())
        .isEqualTo(properties);

    updated = (Branch) getApi().getReference().refName(branch.getName()).get();
    ns = getApi().getNamespace().reference(updated).namespace(namespace).get();
    soft.assertThat(ns.getProperties()).isEqualTo(ImmutableMap.of("key1", "xyz", "key3", "val3"));
    soft.assertThat(ns.getId()).isEqualTo(nsId);
  }
}
