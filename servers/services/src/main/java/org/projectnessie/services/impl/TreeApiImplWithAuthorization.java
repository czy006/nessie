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
package org.projectnessie.services.impl;

import static org.projectnessie.model.Operation.Delete;
import static org.projectnessie.model.Operation.Put;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.FetchOption;
import org.projectnessie.model.ImmutableLogEntry;
import org.projectnessie.model.ImmutableLogResponse;
import org.projectnessie.model.ImmutableReferencesResponse;
import org.projectnessie.model.LogResponse;
import org.projectnessie.model.LogResponse.LogEntry;
import org.projectnessie.model.MergeBehavior;
import org.projectnessie.model.MergeKeyBehavior;
import org.projectnessie.model.MergeResponse;
import org.projectnessie.model.Operation;
import org.projectnessie.model.Operations;
import org.projectnessie.model.Reference;
import org.projectnessie.model.ReferencesResponse;
import org.projectnessie.services.authz.Authorizer;
import org.projectnessie.services.authz.BatchAccessChecker;
import org.projectnessie.services.authz.Check;
import org.projectnessie.services.config.ServerConfig;
import org.projectnessie.versioned.BranchName;
import org.projectnessie.versioned.KeyEntry;
import org.projectnessie.versioned.NamedRef;
import org.projectnessie.versioned.VersionStore;
import org.projectnessie.versioned.WithHash;

/** Does authorization checks (if enabled) on the {@link TreeApiImpl}. */
public class TreeApiImplWithAuthorization extends TreeApiImpl {

  public TreeApiImplWithAuthorization(
      ServerConfig config, VersionStore store, Authorizer authorizer, Principal principal) {
    super(config, store, authorizer, principal);
  }

  @Override
  public ReferencesResponse getAllReferences(FetchOption fetchOption, String filter) {
    ImmutableReferencesResponse.Builder resp = ReferencesResponse.builder();
    BatchAccessChecker check = startAccessCheck();
    List<Reference> refs =
        super.getAllReferences(fetchOption, filter).getReferences().stream()
            .peek(ref -> check.canViewReference(RefUtil.toNamedRef(ref)))
            .collect(Collectors.toList());
    Set<NamedRef> notAllowed =
        check.check().keySet().stream()
            .map(Check::ref)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    refs.stream()
        .filter(ref -> !notAllowed.contains(RefUtil.toNamedRef(ref)))
        .forEach(resp::addReferences);
    return resp.build();
  }

  @Override
  public Reference getReferenceByName(String refName, FetchOption fetchOption)
      throws NessieNotFoundException {
    Reference ref = super.getReferenceByName(refName, fetchOption);
    startAccessCheck().canViewReference(RefUtil.toNamedRef(ref)).checkAndThrow();
    return ref;
  }

  @Override
  public Reference createReference(
      String refName, Reference.ReferenceType type, String targetHash, String sourceRefName)
      throws NessieNotFoundException, NessieConflictException {
    BatchAccessChecker check =
        startAccessCheck().canCreateReference(RefUtil.toNamedRef(type, refName));

    try {
      check.canViewReference(namedRefWithHashOrThrow(sourceRefName, targetHash).getValue());
    } catch (NessieNotFoundException e) {
      // If the default-branch does not exist and hashOnRef points to the "beginning of time",
      // then do not throw a NessieNotFoundException, but re-create the default branch. In all
      // cases, re-throw the exception.
      if (!(Reference.ReferenceType.BRANCH.equals(type)
          && refName.equals(getConfig().getDefaultBranch())
          && (null == targetHash || getStore().noAncestorHash().asString().equals(targetHash)))) {
        throw e;
      }
    }
    check.checkAndThrow();
    return super.createReference(refName, type, targetHash, sourceRefName);
  }

  @Override
  protected Reference assignReference(NamedRef ref, String expectedHash, Reference assignTo)
      throws NessieNotFoundException, NessieConflictException {
    startAccessCheck()
        .canViewReference(
            namedRefWithHashOrThrow(assignTo.getName(), assignTo.getHash()).getValue())
        .canAssignRefToHash(ref)
        .checkAndThrow();
    return super.assignReference(ref, expectedHash, assignTo);
  }

  @Override
  protected void deleteReference(NamedRef ref, String expectedHash)
      throws NessieConflictException, NessieNotFoundException {
    if (ref instanceof BranchName && getConfig().getDefaultBranch().equals(ref.getName())) {
      throw new IllegalArgumentException(
          "Default branch '" + ref.getName() + "' cannot be deleted.");
    }
    startAccessCheck().canDeleteReference(ref).checkAndThrow();
    super.deleteReference(ref, expectedHash);
  }

  @Override
  protected Stream<KeyEntry> filterEntries(
      WithHash<NamedRef> refWithHash, Stream<KeyEntry> entries, String filter) {

    startAccessCheck().canReadEntries(refWithHash.getValue()).checkAndThrow();

    // Post-process the response to only contain content-keys to which the user has access to.
    // Note: no optimization here to return the non-post-processed response to ensure that tests
    // catch potential issues during post-processing early (e.g. a new attribute added to
    // EntriesResponse, but not added here).

    // First, collect the content-keys to which the user has no access to.

    List<KeyEntry> entriesList =
        super.filterEntries(refWithHash, entries, filter).collect(Collectors.toList());

    BatchAccessChecker responseCheck = startAccessCheck();
    entriesList.forEach(
        e ->
            responseCheck.canReadContentKey(
                refWithHash.getValue(), fromKey(e.getKey()), e.getContentId()));
    Set<String> notAllowedContentIds =
        responseCheck.check().keySet().stream().map(Check::contentId).collect(Collectors.toSet());

    // Second, only add the `Entry`s for allowed content keys.

    return entriesList.stream()
        .filter(entry -> !notAllowedContentIds.contains(entry.getContentId()));
  }

  @Override
  protected LogResponse getCommitLog(
      Integer maxRecords,
      FetchOption fetchOption,
      String filter,
      WithHash<NamedRef> endRef,
      String startHash)
      throws NessieNotFoundException {
    NamedRef ref = endRef.getValue();

    startAccessCheck().canListCommitLog(ref).checkAndThrow();
    LogResponse logResponse =
        super.getCommitLog(maxRecords, fetchOption, filter, endRef, startHash);

    Stream<Operation> allOperations =
        logResponse.getLogEntries().stream()
            .map(LogEntry::getOperations)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream);

    // Post-process the response to only contain content-keys to which the user has access to.
    // Note: no optimization here to return the non-post-processed response to ensure that tests
    // catch potential issues during post-processing early (e.g. a new attribute added to
    // LogResponse, but not added here).

    // First, collect the content-keys to which the user has no access to.

    BatchAccessChecker responseCheck = startAccessCheck();
    allOperations.forEach(
        op -> {
          if (op instanceof Put) {
            Put put = (Put) op;
            responseCheck.canReadContentKey(ref, put.getKey(), put.getContent().getId());
          } else if (op instanceof Delete) {
            Delete delete = (Delete) op;
            responseCheck.canReadContentKey(ref, delete.getKey(), null);
          }
        });
    Set<ContentKey> notAllowed =
        responseCheck.check().keySet().stream().map(Check::key).collect(Collectors.toSet());

    // Second, only add the `Operation`s for allowed content keys.

    ImmutableLogResponse.Builder newLogResponse =
        LogResponse.builder().isHasMore(logResponse.isHasMore()).token(logResponse.getToken());
    logResponse.getLogEntries().stream()
        .map(
            e -> {
              ImmutableLogEntry.Builder newLogEntry =
                  LogEntry.builder()
                      .commitMeta(e.getCommitMeta())
                      .parentCommitHash(e.getParentCommitHash())
                      .additionalParents(e.getAdditionalParents());
              if (e.getOperations() != null) {
                e.getOperations().stream()
                    .filter(op -> !notAllowed.contains(op.getKey()))
                    .forEach(newLogEntry::addOperations);
              }
              return newLogEntry.build();
            })
        .forEach(newLogResponse::addLogEntries);
    return newLogResponse.build();
  }

  @Override
  public MergeResponse transplantCommitsIntoBranch(
      String branchName,
      String expectedHash,
      String message,
      List<String> hashesToTransplant,
      String fromRefName,
      Boolean keepIndividualCommits,
      Collection<MergeKeyBehavior> keyMergeTypes,
      MergeBehavior defaultMergeType,
      Boolean dryRun,
      Boolean fetchAdditionalInfo,
      Boolean returnConflictAsResult)
      throws NessieNotFoundException, NessieConflictException {
    if (hashesToTransplant.isEmpty()) {
      throw new IllegalArgumentException("No hashes given to transplant.");
    }

    startAccessCheck()
        .canViewReference(
            namedRefWithHashOrThrow(
                    fromRefName, hashesToTransplant.get(hashesToTransplant.size() - 1))
                .getValue())
        .canCommitChangeAgainstReference(BranchName.of(branchName))
        .checkAndThrow();
    return super.transplantCommitsIntoBranch(
        branchName,
        expectedHash,
        message,
        hashesToTransplant,
        fromRefName,
        keepIndividualCommits,
        keyMergeTypes,
        defaultMergeType,
        dryRun,
        fetchAdditionalInfo,
        returnConflictAsResult);
  }

  @Override
  public MergeResponse mergeRefIntoBranch(
      String branchName,
      String expectedHash,
      String fromRefName,
      String fromHash,
      Boolean keepIndividualCommits,
      @Nullable String message,
      Collection<MergeKeyBehavior> keyMergeTypes,
      MergeBehavior defaultMergeType,
      Boolean dryRun,
      Boolean fetchAdditionalInfo,
      Boolean returnConflictAsResult)
      throws NessieNotFoundException, NessieConflictException {
    startAccessCheck()
        .canViewReference(namedRefWithHashOrThrow(fromRefName, fromHash).getValue())
        .canCommitChangeAgainstReference(BranchName.of(branchName))
        .checkAndThrow();
    return super.mergeRefIntoBranch(
        branchName,
        expectedHash,
        fromRefName,
        fromHash,
        keepIndividualCommits,
        message,
        keyMergeTypes,
        defaultMergeType,
        dryRun,
        fetchAdditionalInfo,
        returnConflictAsResult);
  }

  @Override
  public Branch commitMultipleOperations(String branch, String expectedHash, Operations operations)
      throws NessieNotFoundException, NessieConflictException {
    BranchName branchName = BranchName.of(branch);
    BatchAccessChecker check = startAccessCheck().canCommitChangeAgainstReference(branchName);
    operations
        .getOperations()
        .forEach(
            op -> {
              if (op instanceof Delete) {
                check.canDeleteEntity(branchName, op.getKey(), null);
              } else if (op instanceof Put) {
                Put putOp = (Put) op;
                check.canUpdateEntity(
                    branchName,
                    op.getKey(),
                    putOp.getContent().getId(),
                    putOp.getContent().getType());
              }
            });
    check.checkAndThrow();
    return super.commitMultipleOperations(branch, expectedHash, operations);
  }
}
