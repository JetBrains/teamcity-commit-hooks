package org.eclipse.egit.github.core.service;

import com.google.gson.JsonObject;
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx;
import org.eclipse.egit.github.core.IRepositoryIdProvider;
import org.eclipse.egit.github.core.RepositoryHook;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_HOOKS;
import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_REPOS;

public class RepositoryServiceEx extends RepositoryService {
    public RepositoryServiceEx(@NotNull GitHubClientEx client) {
        super(client);
    }

    public RepositoryHook enableHook(@NotNull IRepositoryIdProvider repository, long hookId) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("active", true);
        return patchHook(repository, hookId, obj);
    }

    public RepositoryHook disableHook(@NotNull IRepositoryIdProvider repository, long hookId) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("active", false);
        return patchHook(repository, hookId, obj);
    }

    public RepositoryHook patchHook(@NotNull IRepositoryIdProvider repository, long hookId, @NotNull JsonObject patch) throws IOException {
        String id = getId(repository);

        StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
        uri.append('/').append(id);
        uri.append(SEGMENT_HOOKS);
        uri.append('/').append(hookId);
        return ((GitHubClientEx) client).patch(uri.toString(), patch, RepositoryHook.class);
    }
}
