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
package org.projectnessie.client.http.v2api;

import org.projectnessie.api.v2.params.EntriesParams;
import org.projectnessie.client.builder.BaseGetEntriesBuilder;
import org.projectnessie.client.http.HttpClient;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.EntriesResponse;
import org.projectnessie.model.Reference;

final class HttpGetEntries extends BaseGetEntriesBuilder<EntriesParams> {

  private final HttpClient client;

  HttpGetEntries(HttpClient client) {
    super(EntriesParams::forNextPage);
    this.client = client;
  }

  @Override
  protected EntriesParams params() {
    return EntriesParams.builder() // TODO: namespace, derive prefix
        .filter(filter)
        .maxRecords(maxRecords)
        .build();
  }

  @Override
  protected EntriesResponse get(EntriesParams p) throws NessieNotFoundException {
    return client
        .newRequest()
        .path("trees/{ref}/entries")
        .resolveTemplate("ref", Reference.toPathString(refName, hashOnRef))
        .queryParam("filter", p.filter())
        .queryParam("page-token", p.pageToken())
        .queryParam("max-records", p.maxRecords())
        .unwrap(NessieNotFoundException.class)
        .get()
        .readEntity(EntriesResponse.class);
  }
}
