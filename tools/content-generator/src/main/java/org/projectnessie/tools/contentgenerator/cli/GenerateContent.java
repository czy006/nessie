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
package org.projectnessie.tools.contentgenerator.cli;

import static java.util.stream.Collectors.toList;
import static org.projectnessie.model.ContentKey.fromPathString;
import static org.projectnessie.tools.contentgenerator.keygen.KeyGenerator.newKeyGenerator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.validation.constraints.Min;
import org.projectnessie.client.api.CommitMultipleOperationsBuilder;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.error.BaseNessieClientServerException;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieReferenceNotFoundException;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.IcebergView;
import org.projectnessie.model.ImmutableDeltaLakeTable;
import org.projectnessie.model.ImmutableIcebergTable;
import org.projectnessie.model.ImmutableIcebergView;
import org.projectnessie.model.Operation.Put;
import org.projectnessie.model.Tag;
import org.projectnessie.model.types.ContentTypes;
import org.projectnessie.tools.contentgenerator.keygen.KeyGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "generate", mixinStandardHelpOptions = true, description = "Generate commits")
public class GenerateContent extends AbstractCommand {

  @Option(
      names = {"-b", "--num-branches"},
      defaultValue = "1",
      description = "Number of branches to use.")
  private int branchCount;

  @Option(
      names = {"-T", "--tag-probability"},
      defaultValue = "0",
      description = "Probability to create a new tag off the last commit.")
  private double newTagProbability;

  @Option(
      names = {"-D", "--default-branch"},
      description =
          "Name of the default branch, uses the server's default branch if not specified.")
  private String defaultBranchName;

  @Min(value = 1, message = "Must create at least one commit.")
  @Option(
      names = {"-n", "--num-commits"},
      required = true,
      defaultValue = "100",
      description = "Number of commits to create.")
  private int numCommits;

  @Option(
      names = {"-d", "--duration"},
      description =
          "Runtime duration, equally distributed among the number of commits to create. "
              + "See java.time.Duration for argument format details.")
  private Duration runtimeDuration;

  @Min(value = 1, message = "Must use at least one table (content-key).")
  @Option(
      names = {"-t", "--num-tables"},
      defaultValue = "1",
      description = "Number of table names, each commit chooses a random table (content-key).")
  private int numTables;

  @Option(
      names = "--type",
      defaultValue = "ICEBERG_TABLE",
      description =
          "Content-types to generate. Defaults to ICEBERG_TABLE. Possible values: "
              + "ICEBERG_TABLE, ICEBERG_VIEW, DELTA_LAKE_TABLE",
      converter = ContentTypeConverter.class)
  private Content.Type contentType;

  @Option(names = "--key-pattern")
  private String keyPattern;

  @Option(names = "--puts-per-commit", defaultValue = "1")
  private int putsPerCommit;

  @Option(names = "--continue-on-error", defaultValue = "false")
  private boolean continueOnError;

  @Spec private CommandSpec spec;

  @Override
  public void execute() throws BaseNessieClientServerException {
    if (runtimeDuration != null) {
      if (runtimeDuration.isZero() || runtimeDuration.isNegative()) {
        throw new ParameterException(
            spec.commandLine(), "Duration must be absent to greater than zero.");
      }
    }

    Duration perCommitDuration =
        Optional.ofNullable(runtimeDuration).orElse(Duration.ZERO).dividedBy(numCommits);

    ThreadLocalRandom random = ThreadLocalRandom.current();

    @SuppressWarnings("JavaTimeDefaultTimeZone")
    String runStartTime =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").format(LocalDateTime.now());

    List<ContentKey> tableNames = generateTableNames(runStartTime);

    try (NessieApiV1 api = createNessieApiInstance()) {
      Branch defaultBranch;
      if (defaultBranchName == null) {
        // Use the server's default branch.
        defaultBranch = api.getDefaultBranch();
      } else {
        // Use the specified default branch.
        try {
          defaultBranch = (Branch) api.getReference().refName(defaultBranchName).get();
        } catch (NessieReferenceNotFoundException e) {
          // Create branch if it does not exist.
          defaultBranch = api.getDefaultBranch();
          defaultBranch =
              (Branch)
                  api.createReference()
                      .reference(Branch.of(defaultBranchName, defaultBranch.getHash()))
                      .sourceRefName(defaultBranch.getName())
                      .create();
        }
      }

      List<String> branches = new ArrayList<>();
      branches.add(defaultBranch.getName());
      while (branches.size() < branchCount) {
        // Create a new branch
        String newBranchName = "branch-" + runStartTime + "_" + (branches.size() - 1);
        Branch branch = Branch.of(newBranchName, defaultBranch.getHash());
        spec.commandLine()
            .getOut()
            .printf(
                "Creating branch '%s' from '%s' at %s%n",
                branch.getName(), defaultBranch.getName(), branch.getHash());
        api.createReference().reference(branch).sourceRefName(defaultBranch.getName()).create();
        branches.add(newBranchName);
      }

      spec.commandLine()
          .getOut()
          .printf("Starting contents generation, %d commits...%n", numCommits);

      for (int commitNum = 0; commitNum < numCommits; commitNum++) {
        // Choose a random branch to commit to
        String branchName = branches.get(random.nextInt(branches.size()));

        Branch commitToBranch = (Branch) api.getReference().refName(branchName).get();

        List<ContentKey> keys =
            IntStream.range(0, putsPerCommit)
                .mapToObj(i -> tableNames.get(random.nextInt(tableNames.size())))
                .distinct()
                .collect(toList());

        Map<ContentKey, Content> existing = api.getContent().refName(branchName).keys(keys).get();

        spec.commandLine()
            .getOut()
            .printf(
                "Committing content-keys '%s' to branch '%s' at %s%n",
                keys.stream().map(ContentKey::toString).collect(Collectors.joining(", ")),
                commitToBranch.getName(),
                commitToBranch.getHash());

        CommitMultipleOperationsBuilder commit =
            api.commitMultipleOperations()
                .branch(commitToBranch)
                .commitMeta(
                    CommitMeta.builder()
                        .message(
                            String.format(
                                "Commit #%d of %d on %s", commitNum, numCommits, branchName))
                        .author(System.getProperty("user.name"))
                        .authorTime(Instant.now())
                        .build());
        for (ContentKey key : keys) {
          Content existingContent = existing.get(key);
          Content newContents = createContents(existingContent, random);
          if (existingContent instanceof IcebergTable || existingContent instanceof IcebergView) {
            commit.operation(Put.of(key, newContents, existingContent));
          } else {
            commit.operation(Put.of(key, newContents));
          }
        }
        try {
          Branch newHead = commit.commit();

          if (random.nextDouble() < newTagProbability) {
            Tag tag = Tag.of("new-tag-" + random.nextLong(), newHead.getHash());
            spec.commandLine()
                .getOut()
                .printf(
                    "Creating tag '%s' from '%s' at %s%n",
                    tag.getName(), branchName, tag.getHash());
            api.createReference().reference(tag).sourceRefName(branchName).create();
          }
        } catch (NessieConflictException e) {
          if (!continueOnError) {
            throw e;
          }

          spec.commandLine()
              .getErr()
              .println(spec.commandLine().getColorScheme().errorText("Conflict: " + e));
        }

        try {
          TimeUnit.NANOSECONDS.sleep(perCommitDuration.toNanos());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    spec.commandLine().getOut().printf("Done creating contents.%n");
  }

  private List<ContentKey> generateTableNames(String runStartTime) {
    IntFunction<ContentKey> mapper;
    if (keyPattern != null) {
      KeyGenerator generator = newKeyGenerator(keyPattern);
      mapper = i -> fromPathString(generator.generate());
    } else {
      mapper =
          i ->
              ContentKey.of(
                  String.format("create-contents-%s", runStartTime),
                  "contents",
                  Integer.toString(i));
    }

    return IntStream.range(0, numTables).mapToObj(mapper).collect(toList());
  }

  private Content createContents(Content currentContents, ThreadLocalRandom random) {
    if (contentType.equals(Content.Type.ICEBERG_TABLE)) {
      ImmutableIcebergTable.Builder icebergBuilder =
          ImmutableIcebergTable.builder()
              .snapshotId(random.nextLong())
              .schemaId(random.nextInt())
              .specId(random.nextInt())
              .sortOrderId(random.nextInt())
              .metadataLocation("metadata " + random.nextLong());
      if (currentContents != null) {
        icebergBuilder.id(currentContents.getId());
      }
      return icebergBuilder.build();
    }
    if (contentType.equals(Content.Type.DELTA_LAKE_TABLE)) {
      ImmutableDeltaLakeTable.Builder deltaBuilder =
          ImmutableDeltaLakeTable.builder()
              .lastCheckpoint("Last checkpoint foo bar " + random.nextLong())
              .addMetadataLocationHistory("metadata location history " + random.nextLong());
      for (int i = 0; i < random.nextInt(4); i++) {
        deltaBuilder
            .lastCheckpoint("Another checkpoint " + random.nextLong())
            .addMetadataLocationHistory("Another metadata location " + random.nextLong());
      }
      if (currentContents != null) {
        deltaBuilder.id(currentContents.getId());
      }
      return deltaBuilder.build();
    }
    if (contentType.equals(Content.Type.ICEBERG_VIEW)) {
      ImmutableIcebergView.Builder viewBuilder =
          ImmutableIcebergView.builder()
              .metadataLocation("metadata " + random.nextLong())
              .versionId(random.nextInt())
              .schemaId(random.nextInt())
              .dialect("Spark-" + random.nextInt())
              .sqlText("SELECT blah FROM meh;");
      if (currentContents != null) {
        viewBuilder.id(currentContents.getId());
      }
      return viewBuilder.build();
    }
    throw new UnsupportedOperationException(
        String.format("Content type %s not supported", contentType.name()));
  }

  public static final class ContentTypeConverter implements ITypeConverter<Content.Type> {

    @Override
    public Content.Type convert(String value) {
      return ContentTypes.forName(value.toUpperCase(Locale.ROOT).trim());
    }
  }
}
