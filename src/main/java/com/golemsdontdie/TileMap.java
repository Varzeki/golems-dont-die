package com.golemsdontdie;

import java.util.Arrays;

/**
 * Packed tiles mapped to packed tiles, without boxing, remembering insertion order.
 *
 * <p>The searches ask constantly whether a tile has been reached and from where; as a
 * {@code HashMap<Long, Long>} each question boxed a key and each tile reached made a key, a
 * value and an entry.
 *
 * <p>Insertion order is kept because the flood's order is part of its answer: the far half of
 * what it reached is where a golem is sent, drawn by index.
 */
final class TileMap
{
	/** Marks an empty slot; a real key equal to it is held apart, so any long is valid. */
	private static final long EMPTY = Long.MIN_VALUE;

	private long[] keys;
	private long[] values;
	private int mask;

	/** Keys in the order added, which is also a search's queue. */
	private long[] order;
	private int size;

	private boolean hasEmptyKey;
	private long emptyKeyValue;

	TileMap(int expected)
	{
		int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) * 2;
		keys = new long[capacity];
		values = new long[capacity];
		Arrays.fill(keys, EMPTY);
		mask = capacity - 1;
		order = new long[Math.max(4, expected)];
	}

	int size()
	{
		return size;
	}

	/** The key added {@code index}th, counting from zero. */
	long keyAt(int index)
	{
		return order[index];
	}

	boolean containsKey(long key)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return true;
			}
			if (at == EMPTY)
			{
				return false;
			}
		}
	}

	/** The value for a key, or {@code missing} if absent. */
	long getOrDefault(long key, long missing)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey ? emptyKeyValue : missing;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot];
			}
			if (at == EMPTY)
			{
				return missing;
			}
		}
	}

	/** The value for a key the map holds; throws if it does not. */
	long get(long key)
	{
		if (key == EMPTY)
		{
			if (!hasEmptyKey)
			{
				throw new IllegalStateException("no such tile");
			}
			return emptyKeyValue;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot];
			}
			if (at == EMPTY)
			{
				throw new IllegalStateException("no such tile");
			}
		}
	}

	/** Adds a key the map does not hold yet; the searches only ever add a tile once. */
	void add(long key, long value)
	{
		if (size == order.length)
		{
			order = Arrays.copyOf(order, size * 2);
		}
		order[size++] = key;
		if (key == EMPTY)
		{
			hasEmptyKey = true;
			emptyKeyValue = value;
			return;
		}
		if (size * 2 > keys.length)
		{
			grow();
		}
		insert(key, value);
	}

	/**
	 * Adds to a key's value, from zero for a key the map does not hold yet.
	 *
	 * @return the value after adding
	 */
	long addTo(long key, long delta)
	{
		if (key == EMPTY)
		{
			if (!hasEmptyKey)
			{
				add(key, delta);
				return delta;
			}
			return emptyKeyValue += delta;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot] += delta;
			}
			if (at == EMPTY)
			{
				add(key, delta);
				return delta;
			}
		}
	}

	/** Empties the map, keeping its capacity. */
	void clear()
	{
		if (size == 0)
		{
			return;
		}
		Arrays.fill(keys, EMPTY);
		size = 0;
		hasEmptyKey = false;
	}

	private void insert(long key, long value)
	{
		int slot = slot(key);
		while (keys[slot] != EMPTY)
		{
			slot = (slot + 1) & mask;
		}
		keys[slot] = key;
		values[slot] = value;
	}

	private void grow()
	{
		long[] oldKeys = keys;
		long[] oldValues = values;
		keys = new long[oldKeys.length * 2];
		values = new long[oldKeys.length * 2];
		Arrays.fill(keys, EMPTY);
		mask = keys.length - 1;
		for (int i = 0; i < oldKeys.length; i++)
		{
			if (oldKeys[i] != EMPTY)
			{
				insert(oldKeys[i], oldValues[i]);
			}
		}
	}

	private int slot(long key)
	{
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32)) & mask;
	}
}
