package net.openosrs.api.query;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryTerminalTest
{
    static class Numbers extends Query<Integer, Numbers>
    {
        Numbers(List<Integer> data) { super(() -> data); }
        @Override protected Numbers self() { return this; }
    }
    @Test void firstAndExistsStopAfterTheFirstUnsortedMatch()
    {
        AtomicInteger visited = new AtomicInteger();
        Numbers query = new Numbers(List.of(1, 2, 3)).keepIf(x -> { visited.incrementAndGet(); return true; });
        assertEquals(1, query.first()); assertEquals(1, visited.get());
        visited.set(0); assertTrue(query.exists()); assertEquals(1, visited.get());
    }
    @Test void sortedDistinctSkipLimitTerminalsPreserveTheOriginalOrder()
    {
        List<Integer> input = List.of(4, 2, 4, 1, 3, 2);
        for (boolean unique : new boolean[]{false, true}) for (boolean sorted : new boolean[]{false, true})
            for (int skip = 0; skip < 8; skip++) for (int limit = 0; limit < 8; limit++)
            {
                Numbers query = new Numbers(input).keepIf(x -> x != 3).skip(skip).limit(limit);
                java.util.List<Integer> expected = new java.util.ArrayList<>(List.of(4, 2, 4, 1, 2));
                if (unique) { query.distinct(); expected = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(expected)); }
                if (sorted) { query.sort(Integer::compareTo); expected.sort(Integer::compareTo); }
                int from = Math.min(skip, expected.size());
                expected = expected.subList(from, from + Math.min(limit, expected.size() - from));
                assertEquals(expected, query.list()); assertEquals(expected.size(), query.count());
                assertEquals(expected.isEmpty() ? null : expected.get(0), query.first());
                assertEquals(expected.isEmpty() ? null : expected.get(expected.size() - 1), query.last());
            }
    }
    @Test void nullAndEmptyBehaviorRemainCompatible()
    {
        Numbers query = new Numbers(Arrays.asList(null, 1, null));
        assertNull(query.first()); assertFalse(query.exists()); assertEquals(3, query.count()); assertNull(query.last());
        assertEquals(Arrays.asList(null, 1), query.distinct().list());
        assertFalse(new Numbers(List.of()).exists());
        assertEquals(List.of(), new Numbers(List.of(1)).limit(-1).list());
    }
}
