/*
 * Copyright (c) 2019, tha23rd <https://https://github.com/tha23rd>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.api.queries;

import static java.lang.Math.abs;
import java.util.function.Predicate;
import net.runelite.api.LocatableQueryResults;
import net.runelite.api.Actor;
import net.runelite.api.Query;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

public abstract class LocatableQuery<EntityType , QueryType> extends Query<EntityType, QueryType, LocatableQueryResults<EntityType>>
{

	// Upstream 1.12.x removed the shared Locatable interface; Actor and TileObject
	// declare identical location accessors separately. Duck-dispatch keeps the
	// classic query DSL intact without altering the upstream API hierarchy.
	private static WorldPoint worldLocationOf(Object o)
	{
		if (o instanceof Actor)
		{
			return ((Actor) o).getWorldLocation();
		}
		if (o instanceof TileObject)
		{
			return ((TileObject) o).getWorldLocation();
		}
		throw new IllegalArgumentException("Not a locatable type: " + o.getClass());
	}

	private static LocalPoint localLocationOf(Object o)
	{
		if (o instanceof Actor)
		{
			return ((Actor) o).getLocalLocation();
		}
		if (o instanceof TileObject)
		{
			return ((TileObject) o).getLocalLocation();
		}
		throw new IllegalArgumentException("Not a locatable type: " + o.getClass());
	}
	@SuppressWarnings("unchecked")
	public QueryType atWorldLocation(WorldPoint location)
	{
		predicate = and(object -> worldLocationOf(object).equals(location));
		return (QueryType) this;
	}

	@SuppressWarnings("unchecked")
	public QueryType atLocalLocation(LocalPoint location)
	{
		predicate = and(object -> localLocationOf(object).equals(location));
		return (QueryType) this;
	}

	@SuppressWarnings("unchecked")
	public QueryType isWithinDistance(LocalPoint to, int distance)
	{
		predicate = and(a -> localLocationOf(a).distanceTo(to) <= distance);
		return (QueryType) this;
	}

	@SuppressWarnings("unchecked")
	public QueryType isWithinDistance(WorldPoint to, int distance)
	{
		predicate = and(a -> worldLocationOf(a).distanceTo(to) <= distance);
		return (QueryType) this;
	}

	@SuppressWarnings("unchecked")
	public QueryType isWithinArea(LocalPoint from, int area)
	{
		predicate = and(a ->
		{
			LocalPoint localLocation = localLocationOf(a);
			return abs(localLocation.getX() - from.getX()) < area
					&& abs(localLocation.getY() - from.getY()) < area;
		});
		return (QueryType) this;
	}

	@SuppressWarnings("unchecked")
	public QueryType filter(Predicate<EntityType> other)
	{
		predicate = and(other);
		return (QueryType) this;
	}
}
