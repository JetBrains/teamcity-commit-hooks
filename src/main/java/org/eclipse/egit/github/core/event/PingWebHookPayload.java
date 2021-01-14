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

import org.eclipse.egit.github.core.RepositoryHook;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.User;

import java.io.Serializable;

/**
 * 'ping' webhook event payload model class.
 */
@SuppressWarnings("unused")
public class PingWebHookPayload implements Serializable {
    private static final long serialVersionUID = 5429499953002724326L;

    private String zen;
    private Integer hook_id;
    private RepositoryHook hook;
    private Repository repository;
    private User sender;

    public String getZen() {
        return zen;
    }

    public void setZen(String zen) {
        this.zen = zen;
    }

    public Integer getHook_id() {
        return hook_id;
    }

    public void setHook_id(Integer hook_id) {
        this.hook_id = hook_id;
    }

    public RepositoryHook getHook() {
        return hook;
    }

    public void setHook(RepositoryHook hook) {
        this.hook = hook;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }
}
