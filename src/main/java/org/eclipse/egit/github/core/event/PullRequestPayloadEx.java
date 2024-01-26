

package org.eclipse.egit.github.core.event;

import org.eclipse.egit.github.core.PullRequestEx;

import java.io.Serializable;

public class PullRequestPayloadEx extends EventPayload implements Serializable {

    private static final long serialVersionUID = -8234504270587265625L + 1;

    private String action;

    private int number;

    private PullRequestEx pullRequest;

    public String getAction() {
        return action;
    }

    public PullRequestPayloadEx setAction(String action) {
        this.action = action;
        return this;
    }

    public int getNumber() {
        return number;
    }

    public PullRequestPayloadEx setNumber(int number) {
        this.number = number;
        return this;
    }

    public PullRequestEx getPullRequest() {
        return pullRequest;
    }

    public PullRequestPayloadEx setPullRequest(PullRequestEx pullRequest) {
        this.pullRequest = pullRequest;
        return this;
    }
}