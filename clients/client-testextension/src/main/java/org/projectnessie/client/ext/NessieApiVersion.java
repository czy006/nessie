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
package org.projectnessie.client.ext;

import java.net.URI;
import org.projectnessie.client.NessieClientBuilder;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.client.api.NessieApiV2;

public enum NessieApiVersion {
  V1("v1", NessieApiV1.class),
  V2("v2", NessieApiV2.class),
  ;

  private final String uriPathElement;
  private final Class<? extends NessieApiV1> clientApiClass;

  NessieApiVersion(String uriPathElement, Class<? extends NessieApiV1> clientApiClass) {
    this.uriPathElement = uriPathElement;
    this.clientApiClass = clientApiClass;
  }

  public URI resolve(URI base) {
    return base.resolve(uriPathElement);
  }

  public NessieApiV1 build(NessieClientBuilder<?> builder) {
    return builder.build(clientApiClass);
  }
}
