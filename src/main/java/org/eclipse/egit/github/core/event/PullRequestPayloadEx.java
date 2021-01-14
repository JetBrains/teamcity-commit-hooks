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
