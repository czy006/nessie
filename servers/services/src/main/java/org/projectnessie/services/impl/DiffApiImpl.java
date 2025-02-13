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

import java.security.Principal;
import java.util.stream.Stream;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.error.NessieReferenceNotFoundException;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.DiffResponse;
import org.projectnessie.model.DiffResponse.DiffEntry;
import org.projectnessie.model.ImmutableDiffResponse;
import org.projectnessie.services.authz.Authorizer;
import org.projectnessie.services.config.ServerConfig;
import org.projectnessie.services.spi.DiffService;
import org.projectnessie.versioned.Diff;
import org.projectnessie.versioned.Hash;
import org.projectnessie.versioned.NamedRef;
import org.projectnessie.versioned.ReferenceNotFoundException;
import org.projectnessie.versioned.VersionStore;
import org.projectnessie.versioned.WithHash;

public class DiffApiImpl extends BaseApiImpl implements DiffService {

  public DiffApiImpl(
      ServerConfig config, VersionStore store, Authorizer authorizer, Principal principal) {
    super(config, store, authorizer, principal);
  }

  @Override
  public DiffResponse getDiff(String fromRef, String fromHash, String toRef, String toHash)
      throws NessieNotFoundException {
    WithHash<NamedRef> from = namedRefWithHashOrThrow(fromRef, fromHash);
    WithHash<NamedRef> to = namedRefWithHashOrThrow(toRef, toHash);
    return getDiff(from.getHash(), to.getHash());
  }

  protected DiffResponse getDiff(Hash from, Hash to) throws NessieNotFoundException {
    ImmutableDiffResponse.Builder builder = ImmutableDiffResponse.builder();
    try {
      try (Stream<Diff> diffs = getStore().getDiffs(from, to)) {
        diffs
            .map(
                diff ->
                    DiffEntry.diffEntry(
                        ContentKey.of(diff.getKey().getElements()),
                        diff.getFromValue().orElse(null),
                        diff.getToValue().orElse(null)))
            .forEach(builder::addDiffs);
      }

    } catch (ReferenceNotFoundException e) {
      throw new NessieReferenceNotFoundException(e.getMessage(), e);
    }
    return builder.build();
  }
}
