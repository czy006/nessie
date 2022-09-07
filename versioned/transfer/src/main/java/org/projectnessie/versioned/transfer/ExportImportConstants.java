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
package org.projectnessie.versioned.transfer;

public final class ExportImportConstants {

  public static final String EXPORT_METADATA = "export-metadata";
  public static final String HEADS_AND_FORKS = "heads-and-forks";
  public static final int DEFAULT_BUFFER_SIZE = 32768;
  public static final int DEFAULT_EXPECTED_COMMIT_COUNT = 1_000_000;
  public static final int DEFAULT_COMMIT_BATCH_SIZE = 20;
  public static final int DEFAULT_ATTACHMENT_BATCH_SIZE = 20;

  private ExportImportConstants() {}
}
