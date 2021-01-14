/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package org.eclipse.egit.github.core.service;

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx;
import org.eclipse.egit.github.core.IRepositoryIdProvider;
import org.eclipse.egit.github.core.RepositoryHook;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_HOOKS;
import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_REPOS;

public class RepositoryServiceEx extends RepositoryService {
    public RepositoryServiceEx(@NotNull GitHubClientEx client) {
        super(client);
    }

    public RepositoryHook enableHook(@NotNull IRepositoryIdProvider repository, long hookId) throws IOException {
        return patchHook(repository, hookId, Collections.<String, Object>singletonMap("active", true));
    }

    public RepositoryHook disableHook(@NotNull IRepositoryIdProvider repository, long hookId) throws IOException {
        return patchHook(repository, hookId, Collections.<String, Object>singletonMap("active", false));
    }

    public RepositoryHook patchHook(@NotNull IRepositoryIdProvider repository, long hookId, @NotNull Map<String, Object> patch) throws IOException {
        String id = getId(repository);

        StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
        uri.append('/').append(id);
        uri.append(SEGMENT_HOOKS);
        uri.append('/').append(hookId);
        return ((GitHubClientEx) client).patch(uri.toString(), patch, RepositoryHook.class);
    }
}
