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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.projectnessie.client.ext.NessieApiVersion;
import org.projectnessie.client.ext.NessieApiVersions;
import org.projectnessie.error.BaseNessieClientServerException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.Reference;
import org.projectnessie.model.Tag;

/** See {@link AbstractTestRest} for details about and reason for the inheritance model. */
public abstract class AbstractRestAssign extends AbstractRest {

  /** Assigning a branch/tag to a fresh main without any commits didn't work in 0.9.2 */
  @ParameterizedTest
  @EnumSource(ReferenceMode.class)
  public void testAssignRefToFreshMain(ReferenceMode refMode)
      throws BaseNessieClientServerException {
    Reference main = getApi().getReference().refName("main").get();
    // make sure main doesn't have any commits
    soft.assertThat(getApi().getCommitLog().refName(main.getName()).stream()).isEmpty();

    Branch testBranch = createBranch("testBranch");
    getApi().assignBranch().branch(testBranch).assignTo(main).assign();
    Reference testBranchRef = getApi().getReference().refName(testBranch.getName()).get();
    soft.assertThat(testBranchRef.getHash()).isEqualTo(main.getHash());

    String testTag = "testTag";
    Reference testTagRef =
        getApi()
            .createReference()
            .sourceRefName(main.getName())
            .reference(Tag.of(testTag, main.getHash()))
            .create();
    soft.assertThat(testTagRef.getHash()).isNotNull();
    getApi()
        .assignTag()
        .hash(testTagRef.getHash())
        .tagName(testTag)
        .assignTo(refMode.transform(main))
        .assign();
    testTagRef = getApi().getReference().refName(testTag).get();
    soft.assertThat(testTagRef.getHash()).isEqualTo(main.getHash());
  }

  @ParameterizedTest
  @EnumSource(ReferenceMode.class)
  public void testAssignBranch(ReferenceMode refMode) throws BaseNessieClientServerException {
    Reference main = createBranch("test-main");
    Branch branch = createBranch("test-branch");

    // make a commit in main
    createCommits(main, 1, 1, main.getHash());
    main = getApi().getReference().refName(main.getName()).get();

    soft.assertThat(branch.getHash()).isNotEqualTo(main.getHash());

    // Assign the test branch to main
    getApi().assignBranch().branch(branch).assignTo(refMode.transform(main)).assign();
    Reference assignedBranch = getApi().getReference().refName(branch.getName()).get();
    soft.assertThat(assignedBranch.getHash()).isEqualTo(main.getHash());
  }

  @ParameterizedTest
  @EnumSource(ReferenceMode.class)
  @NessieApiVersions(versions = NessieApiVersion.V2)
  public void testAssignAndGetBranch(ReferenceMode refMode) throws BaseNessieClientServerException {
    Reference main = createBranch("test-main2");
    Branch branch = createBranch("test-branch2");

    // make a commit in main
    createCommits(main, 1, 1, main.getHash());
    main = getApi().getReference().refName(main.getName()).get();

    soft.assertThat(branch.getHash()).isNotEqualTo(main.getHash());

    // Assign the test branch to main
    Branch assignedBranch =
        getApi().assignBranch().branch(branch).assignTo(refMode.transform(main)).assignAndGet();
    soft.assertThat(assignedBranch.getHash()).isEqualTo(main.getHash());

    Reference currentBranch = getApi().getReference().refName(branch.getName()).get();
    soft.assertThat(assignedBranch).isEqualTo(currentBranch);
  }

  @ParameterizedTest
  @EnumSource(ReferenceMode.class)
  public void testAssignTag(ReferenceMode refMode) throws BaseNessieClientServerException {
    Reference main = createBranch("test-main");
    Reference tag = createTag("testTag", main);

    // make a commit in main
    createCommits(main, 1, 1, main.getHash());
    main = getApi().getReference().refName(main.getName()).get();

    soft.assertThat(tag.getHash()).isNotEqualTo(main.getHash());

    // Assign the test tag to main
    getApi()
        .assignTag()
        .tagName(tag.getName())
        .hash(tag.getHash())
        .assignTo(refMode.transform(main))
        .assign();
    Reference assignedTag = getApi().getReference().refName(tag.getName()).get();
    soft.assertThat(assignedTag.getHash()).isEqualTo(main.getHash());
  }

  @ParameterizedTest
  @EnumSource(ReferenceMode.class)
  @NessieApiVersions(versions = NessieApiVersion.V2)
  public void testAssignAndGetTag(ReferenceMode refMode) throws BaseNessieClientServerException {
    Reference main = createBranch("test-main2");
    Reference tag = createTag("testTag2", main);

    // make a commit in main
    createCommits(main, 1, 1, main.getHash());
    main = getApi().getReference().refName(main.getName()).get();

    soft.assertThat(tag.getHash()).isNotEqualTo(main.getHash());

    // Assign the test tag to main
    Tag assignedTag =
        getApi()
            .assignTag()
            .tagName(tag.getName())
            .hash(tag.getHash())
            .assignTo(refMode.transform(main))
            .assignAndGet();
    soft.assertThat(assignedTag.getHash()).isEqualTo(main.getHash());

    Reference currentTag = getApi().getReference().refName(tag.getName()).get();
    soft.assertThat(assignedTag).isEqualTo(currentTag);
  }
}
