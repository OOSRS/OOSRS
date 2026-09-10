package net.openosrs.api.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

	public List<T> list()
	{
		List<T> result = new ArrayList<>();
		for (T item : source.get())
		{
			boolean keep = true;
			for (Predicate<T> filter : filters)
			{
				if (!filter.test(item))
				{
					keep = false;
					break;
				}
			}
			if (keep)
			{
				result.add(item);
			}
		}
		if (distinct)
		{
			Set<T> unique = new LinkedHashSet<>(result);
			result = new ArrayList<>(unique);
		}
		if (comparator != null)
		{
			result.sort(comparator);
		}
		int from = Math.min(skip, result.size());
		int to = from + Math.min(limit, result.size() - from);
		return new ArrayList<>(result.subList(from, to));
	}

	public T first()
	{
		List<T> result = list();
		return result.isEmpty() ? null : result.get(0);
	}

	public T last()
	{
		List<T> result = list();
		return result.isEmpty() ? null : result.get(result.size() - 1);
	}

	public int count()
	{
		return list().size();
	}

	public boolean exists()
	{
		return first() != null;
	}
}
