

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