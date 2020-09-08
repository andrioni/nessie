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
package com.dremio.nessie.versioned.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.dremio.nessie.backend.simple.InMemory;
import com.dremio.nessie.versioned.BranchName;
import com.dremio.nessie.versioned.Delete;
import com.dremio.nessie.versioned.Hash;
import com.dremio.nessie.versioned.ImmutableBranchName;
import com.dremio.nessie.versioned.ImmutableKey;
import com.dremio.nessie.versioned.ImmutablePut;
import com.dremio.nessie.versioned.ImmutableTagName;
import com.dremio.nessie.versioned.Key;
import com.dremio.nessie.versioned.Put;
import com.dremio.nessie.versioned.ReferenceAlreadyExistsException;
import com.dremio.nessie.versioned.ReferenceConflictException;
import com.dremio.nessie.versioned.ReferenceNotFoundException;
import com.dremio.nessie.versioned.Serializer;
import com.dremio.nessie.versioned.StoreWorker;
import com.dremio.nessie.versioned.TagName;
import com.dremio.nessie.versioned.Unchanged;
import com.dremio.nessie.versioned.VersionStore;
import com.dremio.nessie.versioned.WithHash;
import com.dremio.nessie.versioned.impl.experimental.NessieRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;


class TestJGitVersionStore {
  private static final Hash EMPTY_HASH = Hash.of(ObjectId.zeroId().name());
  private VersionStore<String, String> impl;
  private static InMemory backend;
  private final Random random = new Random();

  @TempDir
  File jgitDir;

  enum RepoType {
    DYNAMO,
    INMEMORY,
    FILE
  }

  @BeforeAll
  public static void init() {
    backend = new InMemory();
  }

  @SuppressWarnings("MissingJavadocMethod")
  @AfterEach
  public void empty() throws IOException {
    backend.close();
  }


  private Repository repository(RepoType repoType) throws IOException {
    final Repository repository;
    switch (repoType) {
      case DYNAMO:
        DfsRepositoryDescription repoDesc = new DfsRepositoryDescription();
        repository = new NessieRepository.Builder().setRepositoryDescription(repoDesc)
                                                   .setBackend(backend.gitBackend())
                                                   .setRefBackend(backend.gitRefBackend())
                                                   .build();
        break;
      case INMEMORY:
        try {
          repository = new InMemoryRepository.Builder().setRepositoryDescription(new DfsRepositoryDescription()).build();
          break;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      case FILE:
        try {
          repository = Git.init().setDirectory(jgitDir).call().getRepository();
          break;
        } catch (GitAPIException e) {
          throw new RuntimeException(e);
        }
      default:
        throw new RuntimeException("Can't reach here");
    }
    return repository;
  }

  void setup(RepoType repoType) {
    try {
      impl = new JGitVersionStore<>(repository(repoType), WORKER);
    } catch (IOException e) {
      throw new RuntimeException("couldn't build repo", e);
    }
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void createAndDeleteTag(RepoType repoType) throws Exception {
    setup(repoType);
    impl.create(BranchName.of("bar"), Optional.empty());
    Optional<Hash> baseCommit = Optional.of(impl.toHash(BranchName.of("bar")));
    TagName tag = TagName.of("foo");

    // check that we can't assign an empty tag.
    assertThrows(ReferenceNotFoundException.class, () -> impl.create(tag, Optional.empty()));

    // create a tag using the default empty hash.
    impl.create(tag, baseCommit);
    assertEquals(baseCommit.get(), impl.toHash(tag));

    // avoid dupe
    assertThrows(ReferenceAlreadyExistsException.class, () -> impl.create(tag, baseCommit));

    // delete without condition
    impl.delete(tag, Optional.empty());

    // create a tag using the default empty hash.
    impl.create(tag, baseCommit);

    // check that wrong id is rejected
    assertThrows(ReferenceConflictException.class, () -> impl.delete(tag, Optional.of(EMPTY_HASH)));

    // delete with correct id.
    impl.delete(tag, baseCommit);

    // avoid create to invalid l1.
    byte[] randomBytes = new byte[20];
    random.nextBytes(randomBytes);
    Hash randomHash = Hash.of(ObjectId.fromRaw(randomBytes).name());
    assertThrows(ReferenceNotFoundException.class, () -> impl.create(tag, Optional.of(randomHash)));

    // fail on delete of non-existent.
    assertThrows(ReferenceNotFoundException.class, () -> impl.delete(tag, Optional.empty()));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void createAndDeleteBranch(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");

    // create a tag using the default empty hash.
    impl.create(branch, Optional.empty());

    // delete without condition
    impl.delete(branch, Optional.empty());

    // create a tag using no commit.
    impl.create(branch, Optional.empty());

    // avoid dupe
    assertThrows(ReferenceAlreadyExistsException.class, () -> impl.create(branch, Optional.empty()));
    assertThrows(ReferenceAlreadyExistsException.class, () -> impl.create(branch, Optional.of(EMPTY_HASH)));

    // check that wrong id is rejected for deletion (non-existing)
    assertThrows(ReferenceConflictException.class, () -> impl.delete(branch, Optional.of(EMPTY_HASH)));

    // delete with correct id.
    impl.delete(branch, Optional.empty());

    // avoid create to invalid l1
    byte[] randomBytes = new byte[20];
    random.nextBytes(randomBytes);
    Hash randomHash = Hash.of(ObjectId.fromRaw(randomBytes).name());
    assertThrows(ReferenceNotFoundException.class, () -> impl.create(branch, Optional.of(randomHash)));

    // fail on delete of non-existent.
    assertThrows(ReferenceNotFoundException.class, () -> impl.delete(branch, Optional.empty()));

    impl.create(branch, Optional.empty());
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "world")));
    // check that wrong id is rejected for deletion (valid but not matching)
    assertThrows(ReferenceConflictException.class, () -> impl.delete(branch, Optional.of(EMPTY_HASH)));

    // can't use tag delete on branch.
    assertThrows(ReferenceNotFoundException.class, () -> impl.delete(TagName.of("foo"), Optional.empty()));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void conflictingCommit(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    impl.create(branch, Optional.empty());
    // first commit.
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "hello world")));

    //first hash.
    Hash originalHash = impl.getCommits(branch).findFirst().get().getHash();

    //second commit.
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "goodbye world")));

    // do an extra commit to make sure it has a different hash even though it has the same value.
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "goodbye world")));

    //attempt commit using first hash which has conflicting key change.
    assertThrows(ReferenceConflictException.class, () -> impl.commit(branch, Optional.of(originalHash),
                                                                     "metadata", ImmutableList.of(Put.of(Key.of("hi"), "my world"))));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void checkRefs(RepoType repoType) throws Exception {
    setup(repoType);
    impl.create(BranchName.of("b1"), Optional.empty());
    Optional<Hash> baseCommit = Optional.of(impl.toHash(BranchName.of("b1")));
    impl.create(BranchName.of("b2"), Optional.empty());
    impl.create(TagName.of("t1"), baseCommit);
    impl.create(TagName.of("t2"), baseCommit);
    assertEquals(ImmutableSet.of("b1", "b2", "t1", "t2"), impl.getNamedRefs()
                                                              .map(wh -> wh.getValue().getName()).collect(Collectors.toSet()));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void checkCommits(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    impl.create(branch, Optional.empty());
    String c1 = "c1";
    String c2 = "c2";
    Key k1 = Key.of("hi");
    String v1 = "hello world";
    String v1p = "goodbye world";
    Key k2 = Key.of("my", "friend");
    String v2 = "not here";
    impl.commit(branch, Optional.empty(), c1, ImmutableList.of(Put.of(k1, v1), Put.of(k2, v2)));
    impl.commit(branch, Optional.empty(), c2, ImmutableList.of(Put.of(k1, v1p)));
    List<WithHash<String>> commits = impl.getCommits(branch).collect(Collectors.toList());
    assertEquals(ImmutableList.of(c2, c1, "none"), commits.stream().map(wh -> wh.getValue()).collect(Collectors.toList()));

    // changed across commits
    assertEquals(v1, impl.getValue(commits.get(1).getHash(), k1));
    assertEquals(v1p, impl.getValue(commits.get(0).getHash(), k1));

    // not changed across commits
    assertEquals(v2, impl.getValue(commits.get(0).getHash(), k2));
    assertEquals(v2, impl.getValue(commits.get(1).getHash(), k2));

    assertEquals(3, impl.getCommits(commits.get(0).getHash()).count());
    assertEquals(2, impl.getCommits(commits.get(1).getHash()).count());
    TagName tag = TagName.of("tag1");
    impl.create(tag, Optional.of(commits.get(0).getHash()));
    assertEquals(3, impl.getCommits(tag).count());
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void assignments(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    final Key k1 = Key.of("p1");
    impl.create(branch, Optional.empty());
    impl.commit(branch, Optional.empty(), "c1", ImmutableList.of(Put.of(k1, "v1")));
    Hash c1 = impl.toHash(branch);
    impl.commit(branch, Optional.empty(), "c1", ImmutableList.of(Put.of(k1, "v2")));
    Hash c2 = impl.toHash(branch);
    TagName t1 = TagName.of("t1");
    BranchName b2 = BranchName.of("b2");

    // ensure tag create assignment is correct.
    impl.create(t1, Optional.of(c1));
    assertEquals("v1", impl.getValue(t1, k1));

    // ensure branch create non-assignment works
    impl.create(b2, Optional.empty());
    assertEquals(null, impl.getValue(b2, k1));

    // ensure tag reassignment is correct.
    impl.assign(t1, Optional.of(c1), c2);
    assertEquals("v2", impl.getValue(t1, k1));

    // ensure branch assignment (no current) is correct
    impl.assign(b2, Optional.empty(), c1);
    assertEquals("v1", impl.getValue(b2, k1));

    // ensure branch assignment (with current) is current
    impl.assign(b2, Optional.of(c1), c2);
    assertEquals("v2", impl.getValue(b2, k1));

  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void delete(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    final Key k1 = Key.of("p1");
    impl.create(branch, Optional.empty());

    impl.commit(branch, Optional.empty(), "c1", ImmutableList.of(Put.of(k1, "v1")));
    assertEquals("v1", impl.getValue(branch, k1));

    impl.commit(branch, Optional.empty(), "c1", ImmutableList.of(Delete.of(k1)));
    assertEquals(null, impl.getValue(branch, k1));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void unchangedOperation(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    impl.create(branch, Optional.empty());
    // first commit.
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "hello world")));

    //first hash.
    Hash originalHash = impl.getCommits(branch).findFirst().get().getHash();

    //second commit.
    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "goodbye world")));

    impl.commit(branch, Optional.empty(), "metadata", ImmutableList.of(Put.of(Key.of("hi"), "goodbye world")));

    //attempt commit using first hash which has conflicting key change.
    assertThrows(ReferenceConflictException.class, () -> impl.commit(branch, Optional.of(originalHash),
                                                                     "metadata", ImmutableList.of(Put.of(Key.of("hi"), "my world"))));

    // attempt commit using first hash, put on on-conflicting key, unchanged on conflicting key.
    assertThrows(ReferenceConflictException.class,
        () -> impl.commit(branch, Optional.of(originalHash), "metadata",
                          ImmutableList.of(Put.of(Key.of("bar"), "mellow"), Unchanged.of(Key.of("hi")))));
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void checkEmptyHistory(RepoType repoType) throws Exception {
    setup(repoType);
    BranchName branch = BranchName.of("foo");
    impl.create(branch, Optional.empty());
    assertEquals(1L, impl.getCommits(branch).count());
  }

  @ParameterizedTest
  @EnumSource(RepoType.class)
  void completeFlow(RepoType repoType) throws Exception {
    VersionStore<String, String> impl = new JGitVersionStore<>(repository(repoType), WORKER);
    final BranchName branch = ImmutableBranchName.builder().name("main").build();
    final BranchName branch2 = ImmutableBranchName.builder().name("b2").build();
    final TagName tag = ImmutableTagName.builder().name("t1").build();
    final Key p1 = ImmutableKey.builder().addElements("my.path").build();
    final String commit1 = "my commit 1";
    final String commit2 = "my commit 2";
    final String v1 = "my.value";
    final String v2 = "my.value2";

    // create a branch
    impl.create(branch, Optional.empty());

    try {
      impl.create(branch, Optional.empty());
      assertFalse(true, "Creating the a branch with the same name as an existing one should fail but didn't.");
    } catch (ReferenceAlreadyExistsException ex) {
      // expected.
    }

    impl.commit(branch,
                Optional.empty(),
                commit1,
                ImmutableList.of(ImmutablePut.<String>builder().key(p1).shouldMatchHash(false).value(v1).build()));

    assertEquals(v1, impl.getValue(branch, p1));

    impl.create(tag, Optional.of(impl.toHash(branch)));

    impl.commit(branch,
                Optional.empty(),
                commit2,
                ImmutableList.of(ImmutablePut.<String>builder().key(p1).shouldMatchHash(false).value(v2).build()));

    assertEquals(v2, impl.getValue(branch, p1));
    assertEquals(v1, impl.getValue(tag, p1));

    List<WithHash<String>> commits = impl.getCommits(branch).collect(Collectors.toList());

    assertEquals(v1, impl.getValue(commits.get(1).getHash(), p1));
    assertEquals(commit1, commits.get(1).getValue());
    assertEquals(v2, impl.getValue(commits.get(0).getHash(), p1));
    assertEquals(commit2, commits.get(0).getValue());

    impl.assign(tag, Optional.of(commits.get(1).getHash()), commits.get(0).getHash());

    assertEquals(commits, impl.getCommits(tag).collect(Collectors.toList()));
    assertEquals(commits, impl.getCommits(commits.get(0).getHash()).collect(Collectors.toList()));

    assertEquals(2, impl.getNamedRefs().count());

    impl.create(branch2, Optional.of(commits.get(1).getHash()));

    impl.delete(branch, Optional.of(commits.get(0).getHash()));

    assertEquals(2, impl.getNamedRefs().count());

    assertEquals(v1, impl.getValue(branch2, p1));


  }

  private static final StoreWorker<String, String> WORKER = new StoreWorker<String, String>() {

    @Override
    public Serializer<String> getValueSerializer() {
      return STRING_SERIALIZER;
    }

    @Override
    public Serializer<String> getMetadataSerializer() {
      return STRING_SERIALIZER;
    }

    @Override
    public Stream<AssetKey> getAssetKeys(String value) {
      return Stream.of();
    }

    @Override
    public CompletableFuture<Void> deleteAsset(AssetKey key) {
      throw new UnsupportedOperationException();
    }
  };

  private static final Serializer<String> STRING_SERIALIZER = new Serializer<String>() {

    @Override
    public ByteString toBytes(String value) {
      return ByteString.copyFrom(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String fromBytes(ByteString bytes) {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  };
}
