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

import org.eclipse.egit.github.core.Commit;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.User;

import java.io.Serializable;
import java.util.List;

/**
 * 'push' webhook event payload model class.
 */
@SuppressWarnings("unused")
public class PushWebHookPayload implements Serializable {

    private static final long serialVersionUID = 4018755847566805828L;

    private String ref;

    private String before;
    private String after;

    // Either head or head_commit
    private String head;
    private Commit head_commit;

    private boolean created;
    private boolean deleted;
    private boolean forced;

    private List<Commit> commits;
    private Repository repository;

    private User pusher;
    private User sender;

    public String getRef() {
        return ref;
    }

    public String getBefore() {
        return before;
    }

    public String getAfter() {
        return after;
    }

    public String getHead() {
        return head;
    }

    public Commit getHead_commit() {
        return head_commit;
    }

    public boolean isCreated() {
        return created;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean isForced() {
        return forced;
    }

    public List<Commit> getCommits() {
        return commits;
    }

    public Repository getRepository() {
        return repository;
    }

    public User getPusher() {
        return pusher;
    }

    public User getSender() {
        return sender;
    }

    public PushWebHookPayload setRef(String ref) {
        this.ref = ref;
        return this;
    }

    public PushWebHookPayload setBefore(String before) {
        this.before = before;
        return this;
    }

    public PushWebHookPayload setAfter(String after) {
        this.after = after;
        return this;
    }

    public PushWebHookPayload setHead(String head) {
        this.head = head;
        return this;
    }

    public PushWebHookPayload setHead_commit(Commit head_commit) {
        this.head_commit = head_commit;
        return this;
    }

    public PushWebHookPayload setCreated(boolean created) {
        this.created = created;
        return this;
    }

    public PushWebHookPayload setDeleted(boolean deleted) {
        this.deleted = deleted;
        return this;
    }

    public PushWebHookPayload setForced(boolean forced) {
        this.forced = forced;
        return this;
    }

    public PushWebHookPayload setCommits(List<Commit> commits) {
        this.commits = commits;
        return this;
    }

    public PushWebHookPayload setRepository(Repository repository) {
        this.repository = repository;
        return this;
    }

    public PushWebHookPayload setPusher(User pusher) {
        this.pusher = pusher;
        return this;
    }

    public PushWebHookPayload setSender(User sender) {
        this.sender = sender;
        return this;
    }
}
