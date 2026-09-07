package com.uni.realtime.gameengine.definition;

/** A {@link GameDefinition} failed a load-time guardrail (§10.5) -- always fatal to the load. */
public final class DefinitionRejectedException extends Exception {

    public DefinitionRejectedException(String message) {
        super(message);
    }
}
