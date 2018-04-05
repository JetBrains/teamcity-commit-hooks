package org.eclipse.egit.github.core;

import org.jetbrains.annotations.Nullable;

public class PullRequestEx extends PullRequest {
    /**
     * serialVersionUID
     */
    private static final long serialVersionUID = 7858604768525096763L + 1;

    @Nullable
    private String mergeCommitSha;

    @Nullable
    public String getMergeCommitSha() {
        return mergeCommitSha;
    }

    public PullRequestEx setMergeCommitSha(@Nullable String mergeCommitSha) {
        this.mergeCommitSha = mergeCommitSha;
        return this;
    }
}
