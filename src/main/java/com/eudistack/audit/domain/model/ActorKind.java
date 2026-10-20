package com.eudistack.audit.domain.model;

/**
 * Kind of actor of the canonical record ({@code types.actor.kind} in the catalogue).
 */
public enum ActorKind {
    PERSON("Person"),
    SYSTEM("System"),
    HOLDER("Holder");

    private final String wireValue;

    ActorKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
