package net.openosrs.api.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Small lazy query base over current service snapshots. */
public abstract class Query<T, Q extends Query<T, Q>>
{
	private final Supplier<List<T>> source;
	private final List<Predicate<T>> filters = new ArrayList<>();
	private Comparator<T> comparator;
	private int skip;
	private int limit = Integer.MAX_VALUE;
	private boolean distinct;

	protected Query(Supplier<List<T>> source)
	{
		this.source = source;
	}

	protected abstract Q self();

	public Q keepIf(Predicate<T> predicate)
	{
		if (predicate == null) throw new IllegalArgumentException("predicate is required");
		filters.add(predicate);
		return self();
	}

	public Q removeIf(Predicate<T> predicate)
	{
		if (predicate == null) throw new IllegalArgumentException("predicate is required");
		filters.add(predicate.negate());
		return self();
	}

	public Q sort(Comparator<T> next)
	{
		if (next == null) throw new IllegalArgumentException("comparator is required");
		comparator = comparator == null ? next : comparator.thenComparing(next);
		return self();
	}

	public Q skip(int count)
	{
		skip = Math.max(0, count);
		return self();
	}

	public Q limit(int count)
	{
		limit = Math.max(0, count);
		return self();
	}

	public Q distinct()
	{
		distinct = true;
		return self();
	}

	private java.util.stream.Stream<T> results()
	{
		java.util.stream.Stream<T> stream = source.get().stream().filter(item ->
		{
			for (Predicate<T> filter : filters) if (!filter.test(item)) return false;
			return true;
		});
		if (distinct) stream = stream.distinct();
		if (comparator != null) stream = stream.sorted(comparator);
		return stream.skip(skip).limit(limit);
	}

	public List<T> list()
	{
		return results().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}

	public T first()
	{
		// Iterator preserves the legacy nullable-first contract; findFirst rejects null.
		java.util.Iterator<T> items = results().iterator();
		return items.hasNext() ? items.next() : null;
	}

	public T last()
	{
		java.util.Iterator<T> items = results().iterator();
		T last = null;
		while (items.hasNext()) last = items.next();
		return last;
	}

	public int count() { return Math.toIntExact(results().count()); }
	public boolean exists() { return first() != null; }
}
