

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