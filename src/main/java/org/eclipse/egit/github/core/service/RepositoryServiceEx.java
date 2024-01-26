

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