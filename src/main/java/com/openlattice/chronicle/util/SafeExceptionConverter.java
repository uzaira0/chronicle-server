package com.openlattice.chronicle.util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/** Exception types and call sites without exception messages or bound values. */
@Plugin(name = "SafeExceptionConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"safeEx"})
public final class SafeExceptionConverter extends LogEventPatternConverter {
    private SafeExceptionConverter() { super("SafeException", "throwable"); }

    public static SafeExceptionConverter newInstance(String[] options) { return new SafeExceptionConverter(); }

    @Override public boolean handlesThrowable() { return true; }

    @Override public void format(LogEvent event, StringBuilder output) {
        if (event.getThrown() == null) {
            return;
        }
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(event.getThrown());
        while (!pending.isEmpty()) {
            Throwable failure = pending.removeFirst();
            if (!seen.add(failure)) {
                continue;
            }
            output.append(failure.getClass().getName());
            for (StackTraceElement frame : failure.getStackTrace()) {
                output.append("\n\tat ").append(frame);
            }
            output.append('\n');
            if (failure.getCause() != null) {
                pending.addLast(failure.getCause());
            }
            Collections.addAll(pending, failure.getSuppressed());
        }
    }
}
