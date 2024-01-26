

package org.eclipse.egit.github.core;

public class RepositoryHookEx extends RepositoryHook {
    private String[] events;

    public RepositoryHookEx() {
    }

    public String[] getEvents() {
        return events;
    }

    public RepositoryHookEx setEvents(String[] events) {
        this.events = events;
        return this;
    }
}