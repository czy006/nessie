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
package org.projectnessie.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.projectnessie.model.LogResponse.LogEntry;
import org.projectnessie.model.ser.Views;

/**
 * This test merely checks the JSON serialization/deserialization of the model classes, with an
 * intention to identify breaking cases whenever jackson version varies.
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestModelObjectsSerialization {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  protected static final String HASH =
      "3e1cfa82b035c26cbbbdae632cea070514eb8b773f616aaeaf668e2f0be8f10e";

  @ParameterizedTest
  @MethodSource("goodCases")
  void testGoodSerDeCases(Case goodCase) throws IOException {
    String json =
        MAPPER.writerWithView(goodCase.serializationView).writeValueAsString(goodCase.obj);
    JsonNode j = MAPPER.readValue(json, JsonNode.class);
    JsonNode d = MAPPER.readValue(goodCase.deserializedJson, JsonNode.class);
    Assertions.assertThat(j).isEqualTo(d);
    Object deserialized = MAPPER.readValue(json, goodCase.deserializeAs);
    Assertions.assertThat(deserialized).isEqualTo(goodCase.obj);
  }

  @ParameterizedTest
  @MethodSource("negativeCases")
  void testNegativeSerDeCases(Case invalidCase) {
    Assertions.assertThatThrownBy(
            () -> MAPPER.readValue(invalidCase.deserializedJson, invalidCase.deserializeAs))
        .isInstanceOf(JsonProcessingException.class);
  }

  static List<Case> goodCases() {
    final Instant now = Instant.now();
    final String branchName = "testBranch";

    return Arrays.asList(
        new Case(
            Branch.of(branchName, HASH),
            Branch.class,
            Json.from("type", "BRANCH").add("name", "testBranch").add("hash", HASH)),
        new Case(
            Branch.of(branchName, null),
            Branch.class,
            Json.from("type", "BRANCH").add("name", "testBranch").addNoQuotes("hash", "null")),
        new Case(
            Tag.of("tagname", HASH),
            Tag.class,
            Json.from("type", "TAG").add("name", "tagname").add("hash", HASH)),
        new Case(
            EntriesResponse.builder()
                .addEntries(
                    ImmutableEntry.builder()
                        .type(Content.Type.ICEBERG_TABLE)
                        .name(ContentKey.fromPathString("/tmp/testpath"))
                        .build())
                .token(HASH)
                .isHasMore(true)
                .build(),
            EntriesResponse.class,
            Json.from("token", HASH)
                .addArrNoQuotes(
                    "entries",
                    Json.from("type", "ICEBERG_TABLE")
                        .addNoQuotes("name", Json.arr("elements", "/tmp/testpath")))
                .addNoQuotes("hasMore", true)),
        new Case(
            CommitMeta.builder()
                .message("msg")
                .hash(HASH)
                .author("a1")
                .author("a2")
                .signedOffBy("s1")
                .signedOffBy("s2")
                .addParentCommitHashes("p1")
                .addParentCommitHashes("p2")
                .committer("c1")
                .putAllProperties("p1", Arrays.asList("v1a", "v1b"))
                .putProperties("p2", "v2")
                .authorTime(Instant.ofEpochSecond(1))
                .commitTime(Instant.ofEpochSecond(2))
                .build(),
            Views.V2.class,
            CommitMeta.class,
            Json.from("hash", HASH)
                .add("committer", "c1")
                .addArr("authors", "a1", "a2")
                .addArr("allSignedOffBy", "s1", "s2")
                .add("message", "msg")
                .add("commitTime", "1970-01-01T00:00:02Z")
                .add("authorTime", "1970-01-01T00:00:01Z")
                .addNoQuotes(
                    "allProperties", Json.arr("p1", "v1a", "v1b").addArr("p2", "v2").toString())
                .addArr("parentCommitHashes", "p1", "p2")),
        new Case(
            LogResponse.builder()
                .token(HASH)
                .addLogEntries(
                    LogEntry.builder()
                        .commitMeta(
                            ImmutableCommitMeta.builder()
                                .commitTime(now)
                                .author("author@example.com")
                                .committer("committer@example.com")
                                .authorTime(now)
                                .hash(HASH)
                                .message("test commit")
                                .putProperties("prop1", "val1")
                                .signedOffBy("signer@example.com")
                                .build())
                        .build())
                .isHasMore(true)
                .build(),
            LogResponse.class,
            Json.from("token", HASH)
                .addArrNoQuotes(
                    "logEntries",
                    Json.noQuotes(
                            "commitMeta",
                            Json.from("hash", HASH)
                                .add("committer", "committer@example.com")
                                .add("author", "author@example.com")
                                .add("signedOffBy", "signer@example.com")
                                .add("message", "test commit")
                                .add("commitTime", now.toString())
                                .add("authorTime", now.toString())
                                .addNoQuotes("properties", Json.from("prop1", "val1")))
                        .addNoQuotes("parentCommitHash", null)
                        .addNoQuotes("operations", null))
                .addNoQuotes("hasMore", true)));
  }

  static List<Case> negativeCases() {
    return Arrays.asList(
        // Special chars in the branch name make it invalid
        new Case(
            Branch.class, Json.from("type", "BRANCH").add("name", "$p@c!@L").add("hash", HASH)),

        // Invalid hash
        new Case(
            Branch.class,
            Json.from("type", "BRANCH").add("name", "testBranch").add("hash", "invalidhash")),

        // No name
        new Case(
            Branch.class,
            Json.from("type", "BRANCH").addNoQuotes("name", "null").add("hash", HASH)),
        new Case(
            Tag.class, Json.from("type", "TAG").add("name", "tagname").add("hash", "invalidhash")));
  }

  protected static class Case {

    final Object obj;
    final Class<?> serializationView;
    final Class<?> deserializeAs;
    final String deserializedJson;

    public Case(Class<?> deserializeAs, Json deserializedJson) {
      this(null, deserializeAs, deserializedJson);
    }

    public Case(Object obj, Class<?> deserializeAs, Json deserializedJson) {
      this(obj, Views.V1.class, deserializeAs, deserializedJson);
    }

    public Case(
        Object obj, Class<?> serializationView, Class<?> deserializeAs, Json deserializedJson) {
      this.obj = obj;
      this.serializationView = serializationView;
      this.deserializeAs = deserializeAs;
      this.deserializedJson = deserializedJson.toString();
    }

    @Override
    public String toString() {
      return deserializeAs.getName() + " : " + obj;
    }
  }

  protected static
  class Json { // Helps in building json strings, which can be used for verification.

    @SuppressWarnings("InlineFormatString")
    private static final String STR_KV_FORMAT = "%s,\"%s\":\"%s\"";

    @SuppressWarnings("InlineFormatString")
    private static final String NO_QUOTES_KV_FORMAT = "%s,\"%s\":%s";

    String currentContent;

    private Json(String currentContent) {
      this.currentContent = currentContent;
    }

    public static Json from(String key, String val) {
      return new Json(String.format("\"%s\":\"%s\"", key, val));
    }

    public static Json noQuotes(String key, Object val) {
      return new Json(String.format("\"%s\":%s", key, val));
    }

    public static Json arr(String key, String... val) {
      String currentContent =
          Stream.of(val)
              .collect(Collectors.joining("\",\"", String.format("\"%s\":[\"", key), "\"]"));
      return new Json(currentContent);
    }

    public Json add(String key, String val) {
      this.currentContent = String.format(STR_KV_FORMAT, currentContent, key, val);
      return this;
    }

    public Json addArr(String key, String... val) {
      String keyContent =
          Stream.of(val)
              .map(v -> String.format("\"%s\"", v))
              .collect(Collectors.joining(",", String.format("\"%s\":[", key), "]"));
      this.currentContent = String.format("%s,%s", currentContent, keyContent);
      return this;
    }

    public Json addArrNoQuotes(String key, Object... val) {
      String keyContent =
          Stream.of(val)
              .map(Object::toString)
              .collect(Collectors.joining(",", String.format("\"%s\":[", key), "]"));
      this.currentContent = String.format("%s,%s", currentContent, keyContent);
      return this;
    }

    public Json addNoQuotes(String key, Object val) {
      String v = val != null ? val.toString() : "null";
      this.currentContent = String.format(NO_QUOTES_KV_FORMAT, currentContent, key, v);
      return this;
    }

    @Override
    public String toString() {
      return String.format("{%s}", currentContent);
    }
  }
}
