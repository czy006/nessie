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
package org.projectnessie.versioned.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.projectnessie.versioned.testworker.OnRefOnly.newOnRef;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.Content;
import org.projectnessie.versioned.BranchName;
import org.projectnessie.versioned.Delete;
import org.projectnessie.versioned.GetNamedRefsParams;
import org.projectnessie.versioned.Hash;
import org.projectnessie.versioned.Key;
import org.projectnessie.versioned.KeyEntry;
import org.projectnessie.versioned.Put;
import org.projectnessie.versioned.ReferenceAlreadyExistsException;
import org.projectnessie.versioned.ReferenceConflictException;
import org.projectnessie.versioned.ReferenceInfo;
import org.projectnessie.versioned.ReferenceNotFoundException;
import org.projectnessie.versioned.Unchanged;
import org.projectnessie.versioned.VersionStore;
import org.projectnessie.versioned.testworker.OnRefOnly;

@ExtendWith(SoftAssertionsExtension.class)
public abstract class AbstractCommits extends AbstractNestedVersionStore {
  @InjectSoftAssertions protected SoftAssertions soft;

  private static final OnRefOnly V_1_1 = newOnRef("v1_1");
  private static final OnRefOnly V_1_2 = newOnRef("v1_2");
  private static final OnRefOnly V_1_3 = newOnRef("v1_3");
  private static final OnRefOnly V_2_1 = newOnRef("v2_1");
  private static final OnRefOnly V_2_2 = newOnRef("v2_2");
  private static final OnRefOnly V_3_1 = newOnRef("v3_1");
  private static final OnRefOnly V_3_2 = newOnRef("v3_2");
  private static final OnRefOnly V_4_1 = newOnRef("v4_1");
  private static final OnRefOnly NEW_v2_1 = newOnRef("new_v2_1");

  protected AbstractCommits(VersionStore store) {
    super(store);
  }

  /*
   * Test:
   * - Create a new branch
   * - Add a commit to it
   * - Check that another commit with no operations can be added with the initial hash
   * - Check the commit can be listed
   * - Check that the commit can be deleted
   */
  @Test
  public void commitToBranch() throws Exception {
    final BranchName branch = BranchName.of("foo");

    final Hash createHash = store().create(branch, Optional.empty());
    final Hash initialHash = store().hashOnReference(branch, Optional.empty());
    soft.assertThat(createHash).isEqualTo(initialHash);

    final Hash commitHash0 =
        store()
            .commit(
                branch,
                Optional.of(initialHash),
                CommitMeta.fromMessage("Some commit"),
                Collections.emptyList());
    final Hash commitHash = store().hashOnReference(branch, Optional.empty());
    soft.assertThat(commitHash).isEqualTo(commitHash0);

    soft.assertThat(commitHash).isNotEqualTo(initialHash);
    store()
        .commit(
            branch,
            Optional.of(initialHash),
            CommitMeta.fromMessage("Another commit"),
            Collections.emptyList());
    final Hash anotherCommitHash = store().hashOnReference(branch, Optional.empty());

    soft.assertThat(commitsList(branch, false))
        .contains(
            commit(anotherCommitHash, "Another commit", commitHash),
            commit(commitHash, "Some commit", initialHash));
    soft.assertThat(commitsList(commitHash, false))
        .contains(commit(commitHash, "Some commit", initialHash));

    soft.assertThatThrownBy(() -> store().delete(branch, Optional.of(initialHash)))
        .isInstanceOf(ReferenceConflictException.class);

    store().delete(branch, Optional.of(anotherCommitHash));
    soft.assertThatThrownBy(() -> store().hashOnReference(branch, Optional.empty()))
        .isInstanceOf(ReferenceNotFoundException.class);
    try (Stream<ReferenceInfo<CommitMeta>> str =
        store().getNamedRefs(GetNamedRefsParams.DEFAULT).filter(this::filterMainBranch)) {
      soft.assertThat(str).isEmpty();
    }
    soft.assertThatThrownBy(() -> store().delete(branch, Optional.of(commitHash)))
        .isInstanceOf(ReferenceNotFoundException.class);
  }

  /*
   * Test:
   * - Create a new branch
   * - Add 3 commits in succession with no conflicts to it with put and delete operations
   * - Check commit metadata
   * - Check keys for each commit hash
   * - Check values for each commit hash
   */
  @Test
  public void commitSomeOperations() throws Exception {
    BranchName branch = BranchName.of("foo");

    Hash base = store().create(branch, Optional.empty());

    Hash initialCommit =
        commit("Initial Commit")
            .put("t1", V_1_1)
            .put("t2", V_2_1)
            .put("t3", V_3_1)
            .toBranch(branch);
    Content t1 = store().getValue(branch, Key.of("t1"));

    Hash secondCommit =
        commit("Second Commit")
            .put("t1", V_1_2.withId(t1), t1)
            .delete("t2")
            .delete("t3")
            .put("t4", V_4_1)
            .toBranch(branch);

    Hash thirdCommit = commit("Third Commit").put("t2", V_2_2).unchanged("t4").toBranch(branch);

    soft.assertThat(commitsList(branch, false))
        .contains(
            commit(thirdCommit, "Third Commit", secondCommit),
            commit(secondCommit, "Second Commit", initialCommit),
            commit(initialCommit, "Initial Commit", base));

    try (Stream<Key> keys = store().getKeys(branch).map(KeyEntry::getKey)) {
      soft.assertThat(keys).containsExactlyInAnyOrder(Key.of("t1"), Key.of("t2"), Key.of("t4"));
    }

    try (Stream<Key> keys = store().getKeys(secondCommit).map(KeyEntry::getKey)) {
      soft.assertThat(keys).containsExactlyInAnyOrder(Key.of("t1"), Key.of("t4"));
    }

    try (Stream<Key> keys = store().getKeys(initialCommit).map(KeyEntry::getKey)) {
      soft.assertThat(keys).containsExactlyInAnyOrder(Key.of("t1"), Key.of("t2"), Key.of("t3"));
    }

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(
                        secondCommit,
                        Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3"), Key.of("t4")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(Key.of("t1"), V_1_2, Key.of("t4"), V_4_1));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(
                        initialCommit,
                        Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3"), Key.of("t4")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_1,
                Key.of("t2"), V_2_1,
                Key.of("t3"), V_3_1));

    soft.assertThat(contentWithoutId(store().getValue(branch, Key.of("t1")))).isEqualTo(V_1_2);
    soft.assertThat(contentWithoutId(store().getValue(branch, Key.of("t2")))).isEqualTo(V_2_2);
    soft.assertThat(store().getValue(branch, Key.of("t3"))).isNull();
    soft.assertThat(contentWithoutId(store().getValue(branch, Key.of("t4")))).isEqualTo(V_4_1);

    soft.assertThat(contentWithoutId(store().getValue(secondCommit, Key.of("t1"))))
        .isEqualTo(V_1_2);
    soft.assertThat(store().getValue(secondCommit, Key.of("t2"))).isNull();
    soft.assertThat(store().getValue(secondCommit, Key.of("t3"))).isNull();
    soft.assertThat(contentWithoutId(store().getValue(secondCommit, Key.of("t4"))))
        .isEqualTo(V_4_1);

    soft.assertThat(contentWithoutId(store().getValue(initialCommit, Key.of("t1"))))
        .isEqualTo(V_1_1);
    soft.assertThat(contentWithoutId(store().getValue(initialCommit, Key.of("t2"))))
        .isEqualTo(V_2_1);
    soft.assertThat(contentWithoutId(store().getValue(initialCommit, Key.of("t3"))))
        .isEqualTo(V_3_1);
    soft.assertThat(store().getValue(initialCommit, Key.of("t4"))).isNull();
  }

  /*
   * Test:
   * - Create a new branch
   * - Add a commit for 3 keys
   * - Add a commit based on initial commit for first key
   * - Add a commit based on initial commit for second key
   * - Add a commit based on initial commit for third  key
   * - Check commit metadata
   * - Check keys for each commit hash
   * - Check values for each commit hash
   */
  @Test
  public void commitNonConflictingOperations() throws Exception {
    BranchName branch = BranchName.of("foo");

    Hash base = store().create(branch, Optional.empty());

    Hash initialCommit =
        commit("Initial Commit")
            .put("t1", V_1_1)
            .put("t2", V_2_1)
            .put("t3", V_3_1)
            .toBranch(branch);
    Content t1 = store().getValue(branch, Key.of("t1"));
    Content t3 = store().getValue(branch, Key.of("t3"));

    Hash t1Commit =
        commit("T1 Commit")
            .fromReference(initialCommit)
            .put("t1", V_1_2.withId(t1), t1)
            .toBranch(branch);
    t1 = store().getValue(branch, Key.of("t1"));

    Hash t2Commit = commit("T2 Commit").fromReference(initialCommit).delete("t2").toBranch(branch);
    Hash t3Commit =
        commit("T3 Commit").fromReference(initialCommit).unchanged("t3").toBranch(branch);
    Hash extraCommit =
        commit("Extra Commit")
            .fromReference(t1Commit)
            .put("t1", V_1_3.withId(t1), t1)
            .put("t3", V_3_2.withId(t3), t3)
            .toBranch(branch);
    Hash newT2Commit =
        commit("New T2 Commit").fromReference(t2Commit).put("t2", NEW_v2_1).toBranch(branch);

    soft.assertThat(commitsList(branch, false))
        .contains(
            commit(newT2Commit, "New T2 Commit", extraCommit),
            commit(extraCommit, "Extra Commit", t3Commit),
            commit(t3Commit, "T3 Commit", t2Commit),
            commit(t2Commit, "T2 Commit", t1Commit),
            commit(t1Commit, "T1 Commit", initialCommit),
            commit(initialCommit, "Initial Commit", base));

    try (Stream<Key> keys = store().getKeys(branch).map(KeyEntry::getKey)) {
      soft.assertThat(keys).containsExactlyInAnyOrder(Key.of("t1"), Key.of("t2"), Key.of("t3"));
    }

    soft.assertThat(
            contentsWithoutId(
                store().getValues(branch, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_3,
                Key.of("t2"), NEW_v2_1,
                Key.of("t3"), V_3_2));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(
                        newT2Commit, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_3,
                Key.of("t2"), NEW_v2_1,
                Key.of("t3"), V_3_2));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(
                        extraCommit, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_3,
                Key.of("t3"), V_3_2));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(t3Commit, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_2,
                Key.of("t3"), V_3_1));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(t2Commit, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_2,
                Key.of("t3"), V_3_1));

    soft.assertThat(
            contentsWithoutId(
                store()
                    .getValues(t1Commit, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_2,
                Key.of("t2"), V_2_1,
                Key.of("t3"), V_3_1));
  }

  /*
   * Test:
   * - Create a new branch
   * - Add a commit to create 2 keys
   * - Add a second commit to delete one key and add a new one
   * - Check that put operations against 1st commit for the 3 keys fail
   * - Check that delete operations against 1st commit for the 3 keys fail
   * - Check that unchanged operations against 1st commit for the 3 keys fail
   * - Check that branch state hasn't changed
   */
  @Test
  public void commitConflictingOperations() throws Exception {
    BranchName branch = BranchName.of("foo");

    store().create(branch, Optional.empty());

    Hash initialCommit =
        commit("Initial Commit").put("t1", V_1_1).put("t2", V_2_1).toBranch(branch);

    Content t1 = store().getValue(branch, Key.of("t1"));
    store().getValue(branch, Key.of("t2"));

    Hash secondCommit =
        commit("Second Commit")
            .put("t1", V_1_2.withId(t1), t1)
            .delete("t2")
            .put("t3", V_3_1)
            .toBranch(branch);
    store().getValue(branch, Key.of("t3"));

    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .put("t1", V_1_3)
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);
    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .put("t2", V_2_2)
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);
    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .put("t3", V_3_2)
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);

    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .delete("t1")
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);
    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .delete("t2")
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);
    soft.assertThatThrownBy(
            () ->
                commit("Conflicting Commit")
                    .fromReference(initialCommit)
                    .delete("t3")
                    .toBranch(branch))
        .isInstanceOf(ReferenceConflictException.class);

    // Checking the state hasn't changed
    soft.assertThat(store().hashOnReference(branch, Optional.empty())).isEqualTo(secondCommit);
  }

  /*
   * Test:
   * - Create a new branch
   * - Add a commit to create 2 keys
   * - Add a second commit to delete one key and add a new one
   * - force commit put operations
   * - Check that put operations against 1st commit for the 3 keys fail
   * - Check that delete operations against 1st commit for the 3 keys fail
   * - Check that unchanged operations against 1st commit for the 3 keys fail
   * - Check that branch state hasn't changed
   */
  @Test
  public void forceCommitConflictingOperations() throws Exception {
    BranchName branch = BranchName.of("foo");

    store().create(branch, Optional.empty());

    commit("Initial Commit").put("t1", V_1_1).put("t2", V_2_1).toBranch(branch);
    Content t1 = store().getValue(branch, Key.of("t1"));

    commit("Second Commit")
        .put("t1", V_1_2.withId(t1), t1)
        .delete("t2")
        .put("t3", V_3_1)
        .toBranch(branch);
    Content t3 = store().getValue(branch, Key.of("t3"));

    Hash putCommit =
        forceCommit("Conflicting Commit")
            .put("t1", V_1_3.withId(t1), t1)
            .put("t2", V_2_2)
            .put("t3", V_3_2.withId(t3), t3)
            .toBranch(branch);

    soft.assertThat(store().hashOnReference(branch, Optional.empty())).isEqualTo(putCommit);
    soft.assertThat(
            contentsWithoutId(
                store().getValues(branch, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_3,
                Key.of("t2"), V_2_2,
                Key.of("t3"), V_3_2));

    Hash unchangedCommit =
        commit("Conflicting Commit")
            .unchanged("t1")
            .unchanged("t2")
            .unchanged("t3")
            .toBranch(branch);
    soft.assertThat(store().hashOnReference(branch, Optional.empty())).isEqualTo(unchangedCommit);
    soft.assertThat(
            contentsWithoutId(
                store().getValues(branch, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3")))))
        .containsExactlyInAnyOrderEntriesOf(
            ImmutableMap.of(
                Key.of("t1"), V_1_3,
                Key.of("t2"), V_2_2,
                Key.of("t3"), V_3_2));

    Hash deleteCommit =
        commit("Conflicting Commit").delete("t1").delete("t2").delete("t3").toBranch(branch);
    soft.assertThat(store().hashOnReference(branch, Optional.empty())).isEqualTo(deleteCommit);
    soft.assertThat(
            store().getValues(branch, Arrays.asList(Key.of("t1"), Key.of("t2"), Key.of("t3"))))
        .isEmpty();
  }

  /*
   * Test:
   *  - Check that store allows storing the same value under different keys
   */
  @Test
  public void commitDuplicateValues() throws Exception {
    BranchName branch = BranchName.of("dupe-values");
    store().create(branch, Optional.empty());
    OnRefOnly foo1 = newOnRef("foo");
    OnRefOnly foo2 = newOnRef("foo");
    store()
        .commit(
            branch,
            Optional.empty(),
            CommitMeta.fromMessage("metadata"),
            ImmutableList.of(put("keyA", foo1), put("keyB", foo2)));

    soft.assertThat(contentWithoutId(store().getValue(branch, Key.of("keyA")))).isEqualTo(foo1);
    soft.assertThat(contentWithoutId(store().getValue(branch, Key.of("keyB")))).isEqualTo(foo2);
  }

  /*
   * Test:
   * - Check that store throws RNFE if branch doesn't exist
   */
  @Test
  public void commitWithInvalidBranch() {
    final BranchName branch = BranchName.of("unknown");

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.empty(),
                        CommitMeta.fromMessage("New commit"),
                        Collections.emptyList()))
        .isInstanceOf(ReferenceNotFoundException.class);
  }

  /*
   * Test:
   * - Check that store throws RNFE if reference hash doesn't exist
   */
  @Test
  public void commitWithUnknownReference()
      throws ReferenceNotFoundException, ReferenceAlreadyExistsException {
    final BranchName branch = BranchName.of("foo");
    store().create(branch, Optional.empty());

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.of(Hash.of("1234567890abcdef")),
                        CommitMeta.fromMessage("New commit"),
                        Collections.emptyList()))
        .isInstanceOf(ReferenceNotFoundException.class);
  }

  /*
   * Test:
   * - Check that store throws IllegalArgumentException if reference hash is not in branch ancestry
   */
  @Test
  public void commitWithInvalidReference()
      throws ReferenceNotFoundException, ReferenceConflictException,
          ReferenceAlreadyExistsException {
    final BranchName branch = BranchName.of("foo");
    store().create(branch, Optional.empty());

    final Hash initialHash = store().hashOnReference(branch, Optional.empty());
    store()
        .commit(
            branch,
            Optional.of(initialHash),
            CommitMeta.fromMessage("Some commit"),
            Collections.emptyList());

    final Hash commitHash = store().hashOnReference(branch, Optional.empty());

    final BranchName branch2 = BranchName.of("bar");
    store().create(branch2, Optional.empty());

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch2,
                        Optional.of(commitHash),
                        CommitMeta.fromMessage("Another commit"),
                        Collections.emptyList()))
        .isInstanceOf(ReferenceNotFoundException.class);
  }

  @Test
  void commitWithValidation() throws Exception {
    BranchName branch = BranchName.of("main");
    Key key = Key.of("my", "table0");
    Hash branchHead = store().getNamedRef(branch.getName(), GetNamedRefsParams.DEFAULT).getHash();
    String cid = "cid-0";

    RuntimeException exception = new ArithmeticException("Whatever");
    soft.assertThatThrownBy(
            () ->
                doCommitWithValidation(
                    branch,
                    cid,
                    key,
                    () -> {
                      // do some operations here
                      try {
                        assertThat(store().getValue(branch, key)).isNull();
                        try (Stream<KeyEntry> ignore = store().getKeys(branch)) {}
                      } catch (ReferenceNotFoundException e) {
                        throw new RuntimeException(e);
                      }

                      // let the custom commit-validation fail
                      throw exception;
                    }))
        .isSameAs(exception);

    soft.assertThat(store().getNamedRef(branch.getName(), GetNamedRefsParams.DEFAULT).getHash())
        .isEqualTo(branchHead);
    soft.assertThat(store().getValue(branch, key)).isNull();
  }

  void doCommitWithValidation(BranchName branch, String cid, Key key, Callable<Void> validator)
      throws Exception {
    store()
        .commit(
            branch,
            Optional.empty(),
            CommitMeta.fromMessage("initial commit meta"),
            Collections.singletonList(Put.of(key, newOnRef("some value"))),
            validator);
  }

  @Test
  void duplicateKeys() {
    BranchName branch = BranchName.of("main");

    Key key = Key.of("my.awesome.table");
    String tableRefState = "table ref state";

    Content createValue1 = newOnRef("no no - not this");
    Content createValue2 = newOnRef(tableRefState);

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.empty(),
                        CommitMeta.fromMessage("initial"),
                        Arrays.asList(Put.of(key, createValue1), Put.of(key, createValue2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(key.toString());

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.empty(),
                        CommitMeta.fromMessage("initial"),
                        Arrays.asList(Unchanged.of(key), Put.of(key, createValue2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(key.toString());

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.empty(),
                        CommitMeta.fromMessage("initial"),
                        Arrays.asList(Delete.of(key), Put.of(key, createValue2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(key.toString());

    soft.assertThatThrownBy(
            () ->
                store()
                    .commit(
                        branch,
                        Optional.empty(),
                        CommitMeta.fromMessage("initial"),
                        Arrays.asList(Delete.of(key), Unchanged.of(key))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(key.toString());
  }
}
