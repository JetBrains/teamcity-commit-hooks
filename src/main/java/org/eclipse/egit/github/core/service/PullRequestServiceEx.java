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
import org.eclipse.egit.github.core.PullRequestEx;
import org.eclipse.egit.github.core.client.GitHubRequest;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_PULLS;
import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_REPOS;

public class PullRequestServiceEx extends PullRequestService {
    public PullRequestServiceEx(@NotNull GitHubClientEx client) {
        super(client);
    }

    public PullRequestEx getPullRequestEx(@NotNull IRepositoryIdProvider repository, int id) throws IOException {
        final String repoId = getId(repository);
        StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
        uri.append('/').append(repoId);
        uri.append(SEGMENT_PULLS);
        uri.append('/').append(id);
        GitHubRequest request = createRequest();
        request.setUri(uri);
        request.setType(PullRequestEx.class);
        return (PullRequestEx) client.get(request).getBody();
    }
}
