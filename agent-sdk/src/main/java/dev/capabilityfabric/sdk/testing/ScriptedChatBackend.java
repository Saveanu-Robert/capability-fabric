package dev.capabilityfabric.sdk.testing;

import dev.capabilityfabric.sdk.model.ChatBackend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * A model stand-in for unit tests: answers from a script, records every call. No network, no model, fully
 * deterministic.
 */
public final class ScriptedChatBackend implements ChatBackend {

    private final Function<Call, String> script;
    private final List<Call> calls = new ArrayList<>();

    public ScriptedChatBackend(Function<Call, String> script) {
        this.script = script;
    }

    /** Answers with the given replies in order, repeating the last one. */
    public static ScriptedChatBackend replies(String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        return new ScriptedChatBackend(call -> queue.size() > 1 ? queue.poll() : queue.peek());
    }

    @Override
    public synchronized Reply chat(Call call) {
        calls.add(call);
        String text = script.apply(call);
        return new Reply(text, call.user().length() / 4, text == null ? 0 : text.length() / 4);
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }
}
