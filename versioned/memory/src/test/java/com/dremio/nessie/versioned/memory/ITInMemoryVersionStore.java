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
package com.dremio.nessie.versioned.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;

import com.dremio.nessie.versioned.StringSerializer;
import com.dremio.nessie.versioned.VersionStore;
import com.dremio.nessie.versioned.VersionStoreException;
import com.dremio.nessie.versioned.tests.AbstractITVersionStore;

public class ITInMemoryVersionStore extends AbstractITVersionStore {
  private static final InMemoryVersionStore.Builder<String, String> BUILDER = InMemoryVersionStore.<String, String>builder()
      .valueSerializer(StringSerializer.getInstance())
      .metadataSerializer(StringSerializer.getInstance());

  private VersionStore<String, String> store;

  @Override
  protected VersionStore<String, String> store() {
    return store;
  }

  @Disabled("NYI")
  protected void checkDiff() throws VersionStoreException {
    super.checkDiff();
  }

  @BeforeEach
  protected void beforeEach() {
    this.store = BUILDER.build();
  }

  @AfterEach
  protected void afterEach() {
    this.store = null;
  }

}
