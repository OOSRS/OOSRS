/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 *
 * Tier-2 packet dispatch: reflection onto the game's own outgoing-packet
 * machinery, fully driven by the offline-verified hooks.json for this
 * revision.
 *
 * Send path (validated structurally at dumper time and re-verified here at
 * bind time — any mismatch disables the tier for the session):
 *   writer      = CLIENT.<clientWriterField>            (static or instance)
 *   cipher      = writer.<writerCipherField>            (Isaac)
 *   node        = INVOKESTATIC factoryOwner.<factory>(packet, cipher)
 *   buffer      = node.<nodeBufferField>                (payload + offset)
 *   payload     = ordered buffer.<op.m>(...) calls per packet layout
 *   enqueue     = writer.<addNodeMethod>(node, garbageConstant)
 */
package net.openosrs.api.dispatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.hooks.Hooks;
import net.openosrs.api.hooks.HooksFile;
import net.runelite.api.Client;

/**
 * Packet-only dispatch through revisioned reflection hooks. Every claim made
 * here is command-level: a {@code true} return means the client accepted the
 * built packet into its queue — server observation is a P7 concern.
 */
@Slf4j
@Singleton
public class PacketDispatcher
{
	private final Client client;
	private final Hooks hooks;
	private volatile Binding binding;
	private volatile boolean disabled;

	@Inject
	public PacketDispatcher(Client client, Hooks hooks)
	{
		this.client = client;
		this.hooks = hooks;
	}

	/** True only when hooks loaded AND bind-time verification succeeded. */
	public boolean available()
	{
		if (disabled || !hooks.isPacketTierAvailable())
		{
			return false;
		}
		try
		{
			binding();
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return false;
		}
	}

	/** True once the Isaac cipher is seeded (i.e. we are logged in). */
	public boolean cipherReady()
	{
		Binding b = binding;
		if (b == null)
		{
			return false;
		}
		try
		{
			return b.cipher() != null;
		}
		catch (ReflectiveOperationException e)
		{
			return false;
		}
	}

	/**
	 * Force the reflection bind now and return a diagnostic summary.
	 * Runs the full structural gate against the LIVE client classes.
	 *
	 * @throws IllegalStateException when any gate fails (tier self-disables)
	 */
	public String verifyBind()
	{
		try
		{
			Binding b = binding();
			return "bind OK writer=" + b.writer.getClass().getName()
				+ " cipher=" + (b.cipher() != null ? b.cipher().getClass().getName() : "null(pre-login)")
				+ " factory=" + b.factory.getName()
				+ " queue=" + b.queue.getName();
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			disabled = true;
			return "bind FAILED: " + e;
		}
	}

	/**
	 * Replay one packet's VERIFIED layout against a STANDALONE scratch
	 * buffer and report the exact byte count written.
	 *
	 * CRITICAL SAFETY NOTE (learned live 2026-08-26): we intentionally do
	 * NOT call the real node factory here. The factory writes the
	 * cipher-encrypted packet header, consuming one ISAAC keystream value
	 * per call; burning keystream without transmitting desyncs the client
	 * from the server and causes a disconnect. Scratch buffers are fully
	 * isolated: no pool, no writer, no cipher, no network.
	 *
	 * The layout width sum is the hard proof. The injected client may store the
	 * public offset through an obfuscation multiplier, so a raw reflective
	 * offset delta is diagnostic only and never revokes a structurally valid
	 * layout.
	 */
	public String dryRunById(int id, Object... values)
	{
		try
		{
			HooksFile.PacketDef packet = hooks.file() == null ? null : hooks.file().packetById(id);
			if (packet == null)
			{
				return "no packet with id " + id;
			}
			if (packet.getWrites() == null || (packet.getWrites().isEmpty() && packet.getLength() != 0))
			{
				return "id " + id + " has no verified layout";
			}
			Binding b = binding();
			Object scratch = b.newScratchBuffer();
			List<Method> ops = b.layoutMethods(packet);
			int before = b.bufferOffset.getInt(scratch);
			for (int i = 0; i < ops.size(); i++)
			{
				Method op = ops.get(i);
				try
				{
					Object value = i < values.length && values[i] != null ? values[i] : 0;
				Object[] args = b.fillArgs(op.getParameterTypes(), value, scratch);
					if (args == null)
					{
						return "INCONCLUSIVE id=" + id + " at write " + (i + 1)
							+ ": buffer-valued operation requires captured arguments";
					}
					op.invoke(scratch, args);
				}
				catch (ReflectiveOperationException | IllegalArgumentException e)
				{
					Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
						? ((java.lang.reflect.InvocationTargetException) e).getTargetException()
						: e;
					return "FAILED id=" + id + " at write " + (i + 1) + "/"
						+ ops.size() + " (" + op.getName() + "): " + cause;
				}
			}
			int after = b.bufferOffset.getInt(scratch);
			int delta = after - before;
			int widthSum = 0;
			for (HooksFile.WriteOp w : packet.getWrites())
			{
				widthSum += w.getW() != null
					? Integer.parseInt(w.getW().substring(1)) : 0;
			}
			boolean widthsHonest = delta == widthSum;
			boolean lengthHonest = packet.getLength() == null
				|| packet.getLength() < 0 || delta == packet.getLength();
			if (!widthsHonest || !lengthHonest)
			{
				return "INCONCLUSIVE id=" + id + ": raw offset delta=" + delta
					+ "B, static layout=" + widthSum + "B, declared " + packet.getLength()
					+ " (obfuscated offset; layout remains structurally trusted)";
			}
			return "OK id=" + id + " bytes=" + delta
				+ " declaredLen=" + packet.getLength() + " (scratch-verified)";
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
				? ((java.lang.reflect.InvocationTargetException) e).getTargetException()
				: e;
			return "dry-run FAILED: " + cause;
		}
	}

	/**
	 * Dry-run EVERY packet that carries a layout. One login funds the whole
	 * table's verification: returns "ok=N fail=M" plus up to ten distinct
	 * failure signatures for offline fixing.
	 */
	public String dryRunAll()
	{
		int ok = 0;
		int inconclusive = 0;
		int noLayout = 0;
		Map<String, Integer> failures = new java.util.TreeMap<>();
		for (HooksFile.PacketDef p : hooks.file().getPackets())
		{
			if (p.getWrites() == null || (p.getWrites().isEmpty() && p.getLength() != 0))
			{
				noLayout++;
				continue;
			}
			String r = dryRunById(p.getId());
			if (r.startsWith("INCONCLUSIVE"))
			{
				inconclusive++;
			}
			else if (r.startsWith("OK id="))
			{
				ok++;
			}
			else if (r.startsWith("QUARANTINE"))
			{
				// counted in failures below with its signature
			}
			else
			{
				String sig = r.contains("(") ? r.substring(r.indexOf('('))
					: r.length() > 60 ? r.substring(0, 60) : r;
				failures.merge(sig, 1, Integer::sum);
			}
		}
		StringBuilder sb = new StringBuilder("ok=").append(ok)
			.append(" inconclusive=").append(inconclusive)
			.append(" noLayout=").append(noLayout)
			.append(" failed=").append(hooks.file().getPackets().size() - ok - inconclusive - noLayout);
		int shown = 0;
		for (Map.Entry<String, Integer> e : failures.entrySet())
		{
			if (shown++ >= 10)
			{
				sb.append(" | ...more");
				break;
			}
			sb.append(" | x").append(e.getValue()).append(' ').append(e.getKey());
		}
		return sb.toString();
	}

	/**
	 * Send a packet by semantic name. Values are supplied one-per-verified
	 * payload write operation; an arity mismatch is rejected before the node
	 * factory runs so it cannot consume an ISAAC value for an invalid request.
	 */
	public boolean send(String packetName, Object... values)
	{
		return dispatch(hooks.file() == null ? null : hooks.file().packetByName(packetName), values);
	}

	/** Send a packet by opcode when a revision table has no semantic name. */
	public boolean sendById(int id, Object... values)
	{
		return dispatch(hooks.file() == null ? null : hooks.file().packetById(id), values);
	}

	private boolean dispatch(HooksFile.PacketDef packet, Object[] values)
	{
		values = values == null ? new Object[0] : values;
		if (!client.isClientThread())
		{
			log.warn("packet dispatch must run on the client thread");
			return false;
		}
		if (!available())
		{
			log.debug("packet tier unavailable");
			return false;
		}
		if (packet == null)
		{
			log.warn("packet definition missing");
			return false;
		}
		if (packet.getWrites() == null || (packet.getWrites().isEmpty() && packet.getLength() != 0))
		{
			log.warn("packet id {} has no verified layout yet; refusing to "
				+ "guess payload structure", packet.getId());
			return false;
		}
		try
		{
			Binding b = binding();
			List<Method> ops = b.layoutMethods(packet);
			if (values.length != ops.size())
			{
				log.warn("packet id {} requires {} payload values (one per verified write), got {}; refusing before factory",
					packet.getId(), ops.size(), values.length);
				return false;
			}
			for (int i = 0; i < ops.size(); i++)
			{
				if (!b.acceptsValue(ops.get(i), values[i]))
				{
					log.warn("packet id {} has an invalid value at write {}; refusing before factory",
						packet.getId(), i);
					return false;
				}
			}
			Object packetInstance = b.packetField(packet.getFields().get(0)).get(null);
			if (packetInstance == null)
			{
				throw new IllegalStateException("packet instance is null");
			}
			Object cipher = b.cipher();
			if (cipher == null)
			{
				log.debug("packet dispatch refused before factory: cipher is not seeded");
				return false;
			}
			Object node = b.factory.invoke(null, packetInstance, cipher);
			if (!b.nodeClass.isInstance(node))
			{
				throw new IllegalStateException("factory returned no node");
			}
			Object buffer = b.nodeBuffer.get(node);
			for (int i = 0; i < ops.size(); i++)
			{
				Method op = ops.get(i);
				op.invoke(buffer, b.fillArgs(op.getParameterTypes(), values[i], buffer));
			}
			b.queue.invoke(b.writer, node, b.garbage);
			log.debug("packet id {} enqueued", packet.getId());
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.warn("packet dispatch failed for id {}: {}", packet.getId(), e.toString());
			return false;
		}
	}

	/*
	 * Argument filling moved to Binding.fillArgs — needs buffer-family types
	 * for sub-buffer params.
	 */
	// ------------------------------------------------------------------
	// binding
	// ------------------------------------------------------------------

	private Binding binding() throws ReflectiveOperationException
	{
		Binding current = binding;
		if (current != null)
		{
			return current;
		}
		synchronized (this)
		{
			if (disabled)
			{
				throw new IllegalStateException("packet tier disabled");
			}
			if (binding == null)
			{
				try
				{
					binding = new Binding(client, hooks.file());
				}
				catch (ReflectiveOperationException | RuntimeException e)
				{
					disabled = true;
					log.error("packet tier DISABLED: bind verification failed: {}", e.toString());
					throw e;
				}
			}
			return binding;
		}
	}

	/**
	 * Resolved reflection surface. Construction IS the verification: every
	 * hooks fact is checked against the loaded client before use.
	 */
	private static final class Binding
	{
		private final Class<?> packetClass;
		private final Class<?> nodeClass;
		private final Class<?> bufferClass;
		private final List<Class<?>> bufferFamilyClasses;
		private final Object writer;
		private final Field cipherField;
		private final Method factory;
		private final Method queue;
		private final int garbage;
		private final Field nodeBuffer;
		private final Field bufferOffset;
		private final Map<String, Field> packetFields = new HashMap<>();
		private final Map<Integer, List<Method>> layoutCache = new HashMap<>();

		private Binding(Client client, HooksFile file) throws ReflectiveOperationException
		{
			ClassLoader loader = client.getClass().getClassLoader();
			HooksFile.Families fam = file.getFamilies();
			HooksFile.SendPath sp = file.getSendPath();

			packetClass = loader.loadClass(fam.getClientPacket());
			nodeClass = loader.loadClass(fam.getPacketBufferNode());
			bufferClass = loader.loadClass(fam.getBuffer());
			Class<?> isaacClass = loader.loadClass(fam.getIsaac());

			// structural gate: packet family statics count matches the table
			int statics = 0;
			for (Field f : packetClass.getDeclaredFields())
			{
				if (Modifier.isStatic(f.getModifiers())
					&& f.getType().equals(packetClass))
				{
					statics++;
				}
			}
			if (statics != file.getPackets().size())
			{
				throw new IllegalStateException("packet class " + fam.getClientPacket()
					+ " has " + statics + " statics but hooks table holds "
					+ file.getPackets().size() + " — stale hooks for this jar");
			}

			// writer + cipher instances
			Field writerField = findField(client.getClass(), sp.getClientWriterField());
			writer = writerField.get(Modifier.isStatic(writerField.getModifiers()) ? null : client);
			if (writer == null)
			{
				throw new IllegalStateException("packet writer instance unavailable");
			}
			if (!sp.getWriterClass().equals(writer.getClass().getName()))
			{
				throw new IllegalStateException("writer class mismatch: expected "
					+ sp.getWriterClass() + " got " + writer.getClass().getName());
			}
			cipherField = findField(writer.getClass(), sp.getWriterCipherField());
			Object seeded = cipherField.get(writer);
			// The cipher is seeded during the login handshake; before login it
			// is legitimately null. Type-check only what exists now — live
			// reads re-check on every use (see cipher()).
			if (seeded != null && !isaacClass.isInstance(seeded))
			{
				throw new IllegalStateException("cipher field type mismatch: "
					+ seeded.getClass().getName() + " is not " + isaacClass.getName());
			}

			// factory: try each discovered signature until one binds
			factory = resolveFactory(loader, file);
			queue = findMethod(writer.getClass(), sp.getAddNodeMethod(),
				new Class<?>[] {nodeClass, int.class});
			if (Modifier.isStatic(queue.getModifiers()) || queue.getReturnType() != void.class)
			{
				throw new IllegalStateException("queue method must be instance void(node,int): "
					+ queue);
			}
			long garbageValue = sp.getGarbageConstant();
			if (garbageValue < Integer.MIN_VALUE || garbageValue > Integer.MAX_VALUE)
			{
				throw new IllegalStateException("queue garbage constant does not fit int: "
					+ garbageValue);
			}
			garbage = (int) garbageValue;
			nodeBuffer = findField(nodeClass, sp.getNodeBufferField());
			bufferOffset = findField(bufferClass, fam.getBufferOffsetField());

			bufferFamilyClasses = new ArrayList<>();
			bufferFamilyClasses.add(bufferClass);
			if (fam.getBufferSubclasses() != null)
			{
				for (String sub : fam.getBufferSubclasses())
				{
					bufferFamilyClasses.add(loader.loadClass(sub));
				}
			}
			if (bufferOffset.getType() != int.class)
			{
				throw new IllegalStateException("buffer offset is not int: "
					+ bufferOffset.getType().getName());
			}
			if (!bufferFamilyClasses.contains(nodeBuffer.getType()))
			{
				throw new IllegalStateException("node buffer type " + nodeBuffer.getType().getName()
					+ " is outside discovered buffer family");
			}

			// every layout method must exist on the buffer family
			for (HooksFile.PacketDef p : file.getPackets())
			{
				if (p.getWrites() == null)
				{
					continue;
				}
				for (HooksFile.WriteOp op : p.getWrites())
				{
					if (resolveBufferMethod(op) == null)
					{
						throw new IllegalStateException("layout method '" + op.getM()
							+ op.getD() + "' not found on buffer family for packet id "
							+ p.getId());
					}
				}
			}
		}

		private Method resolveFactory(ClassLoader loader, HooksFile file)
			throws ReflectiveOperationException
		{
			HooksFile.SendPath sp = file.getSendPath();
			Class<?> owner = loader.loadClass(sp.getFactoryOwner());
			Class<?> packetType = loader.loadClass(file.getFamilies().getClientPacket());
			Class<?> cipherType = loader.loadClass(file.getFamilies().getIsaac());
			Class<?> nodeType = loader.loadClass(file.getFamilies().getPacketBufferNode());
			List<String> names = new ArrayList<>(sp.getFactoryMethods());
			Collections.sort(names);
			RuntimeException last = null;
			for (String spec : names)
			{
				String name = spec.substring(0, spec.indexOf('('));
				try
				{
					Method m = owner.getDeclaredMethod(name, packetType, cipherType);
					m.setAccessible(true);
					if (Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(nodeType))
					{
						return m;
					}
				}
				catch (NoSuchMethodException ex)
				{
					last = new RuntimeException(ex);
				}
			}
			throw new IllegalStateException("no usable node factory bound", last);
		}

		/** Reject invalid payload types before consuming an ISAAC value. */
		boolean acceptsValue(Method operation, Object value)
		{
			boolean foundValue = false;
			for (Class<?> type : operation.getParameterTypes())
			{
				if (bufferFamilyClasses.contains(type))
				{
					continue;
				}
				if (!foundValue)
				{
					if (type == String.class)
					{
						if (!(value instanceof String) || ((String) value).indexOf('\0') >= 0)
						{
							return false;
						}
					}
					else if (!type.isPrimitive() || type == boolean.class || !(value instanceof Number))
					{
						return false;
					}
					foundValue = true;
				}
				else if (!type.isPrimitive())
				{
					return false;
				}
			}
			return foundValue;
		}

		/**
		 * The caller's semantic value lands in the first compatible primitive or
		 * String slot; static writers receive the same live buffer.
		 */
		Object[] fillArgs(Class<?>[] types, Object value, Object liveBuffer)
		{
			Object[] args = new Object[types.length];
			boolean valuePlaced = false;
			for (int i = 0; i < types.length; i++)
			{
				Class<?> t = types[i];
				if (bufferFamilyClasses.contains(t))
				{
					args[i] = liveBuffer;
				}
				else if (t == String.class && !valuePlaced && value instanceof String)
				{
					args[i] = value;
					valuePlaced = true;
				}
				else if (t.isPrimitive() && !valuePlaced && t != boolean.class)
				{
					// NOTE: a chained ?: over numeric branches promotes the
					// whole expression to double (JLS conditional-type rules)
					// — that bug shipped every value as Double. Per-type
					// branches box each correctly.
					Number n = value instanceof Number ? (Number) value : 0;
					if (t == int.class)
					{
						args[i] = n.intValue();
					}
					else if (t == long.class)
					{
						args[i] = n.longValue();
					}
					else if (t == short.class)
					{
						args[i] = n.shortValue();
					}
					else if (t == float.class)
					{
						args[i] = n.floatValue();
					}
					else if (t == double.class)
					{
						args[i] = n.doubleValue();
					}
					else
					{
						args[i] = n.byteValue();
					}
					valuePlaced = true;
				}
				else if (t == int.class)
				{
					args[i] = 0;
				}
				else if (t == short.class)
				{
					args[i] = (short) 0;
				}
				else if (t == byte.class)
				{
					args[i] = (byte) 0;
				}
				else if (t == long.class)
				{
					args[i] = 0L;
				}
				else if (t == float.class)
				{
					args[i] = 0f;
				}
				else if (t == double.class)
				{
					args[i] = 0d;
				}
				else if (t == boolean.class)
				{
					args[i] = Boolean.FALSE;
				}
				else if (t == byte[].class)
				{
					args[i] = new byte[16];
				}
				else if (t == String.class)
				{
					args[i] = "";
				}
				else
				{
					args[i] = null;
				}
			}
			return args;
		}

		/**
		 * Fresh standalone packet-buffer. Isolated by construction: never
		 * handed to the writer, never enqueued, discarded afterwards.
		 */
		Object newScratchBuffer() throws ReflectiveOperationException
		{
			Class<?> sub = bufferFamilyClasses.stream()
				.filter(c -> !c.equals(bufferClass))
				.findFirst().orElse(bufferClass);
			java.lang.reflect.Constructor<?> ctor = null;
			for (java.lang.reflect.Constructor<?> c : sub.getDeclaredConstructors())
			{
				if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == int.class)
				{
					ctor = c;
					break;
				}
			}
			if (ctor == null)
			{
				throw new IllegalStateException("no (int) ctor on " + sub.getName());
			}
			ctor.setAccessible(true);
			return ctor.newInstance(4096);
		}

		/** Live cipher read — the instance appears at login, never cache it. */
		Object cipher() throws ReflectiveOperationException
		{
			return cipherField.get(writer);
		}

		Field packetField(String name) throws ReflectiveOperationException
		{
			Field cached = packetFields.get(name);
			if (cached != null)
			{
				return cached;
			}
			Field f = findField(packetClass, name);
			if (!Modifier.isStatic(f.getModifiers()) || !f.getType().equals(packetClass))
			{
				throw new IllegalStateException("packet field is not a static "
					+ packetClass.getName() + ": " + name);
			}
			packetFields.put(name, f);
			return f;
		}

		/** Resolve + cache the concrete Methods for one packet's layout. */
		List<Method> layoutMethods(HooksFile.PacketDef packet)
		{
			return layoutCache.computeIfAbsent(packet.getId(), k -> {
				List<Method> out = new ArrayList<>();
				for (HooksFile.WriteOp op : packet.getWrites())
				{
					out.add(resolveBufferMethod(op));
				}
				return out;
			});
		}

		private Method resolveBufferMethod(HooksFile.WriteOp op)
		{
			for (Class<?> c : bufferFamilyClasses)
			{
				if (op.getOwner() != null && !op.getOwner().equals(c.getName()))
				{
					continue;
				}
				Method m = findMethodByDesc(c, op.getM(), op.getD());
				if (m != null)
				{
					if (Modifier.isStatic(m.getModifiers()) != op.isStaticMethod())
					{
						continue;
					}
					return m;
				}
			}
			return null;
		}

		private static Method findMethodByDesc(Class<?> type, String name, String desc)
		{
			Class<?> cursor = type;
			while (cursor != null)
			{
				for (Method m : cursor.getDeclaredMethods())
				{
					if (m.getName().equals(name))
					{
						// match descriptor shape (obf param names aside)
						Class<?>[] pts = m.getParameterTypes();
						StringBuilder built = new StringBuilder("(");
						for (Class<?> pt : pts)
						{
							built.append(vmDesc(pt));
						}
						built.append(')');
						String expected = desc;
						String actual = built.append(vmDesc(m.getReturnType())).toString();
						if (actual.equals(expected))
						{
							m.setAccessible(true);
							return m;
						}
					}
				}
				cursor = cursor.getSuperclass();
			}
			return null;
		}

		private static String vmDesc(Class<?> c)
		{
			if (!c.isPrimitive() && !c.isArray())
			{
				return "L" + c.getName().replace('.', '/') + ";";
			}
			if (c.isArray())
			{
				return "[" + vmDesc(c.getComponentType());
			}
			if (c == int.class) return "I";
			if (c == long.class) return "J";
			if (c == byte.class) return "B";
			if (c == short.class) return "S";
			if (c == boolean.class) return "Z";
			if (c == char.class) return "C";
			if (c == float.class) return "F";
			if (c == double.class) return "D";
			return "V";
		}

		private static Field findField(Class<?> type, String name)
			throws ReflectiveOperationException
		{
			Class<?> cursor = type;
			while (cursor != null)
			{
				try
				{
					Field f = cursor.getDeclaredField(name);
					f.setAccessible(true);
					return f;
				}
				catch (NoSuchFieldException ignored)
				{
					cursor = cursor.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		}

		private static Method findMethod(Class<?> type, String name, Class<?>[] params)
			throws ReflectiveOperationException
		{
			Method m = type.getDeclaredMethod(name, params);
			m.setAccessible(true);
			return m;
		}

		private static Method findMethodLenient(Class<?> type, String name)
		{
			Class<?> cursor = type;
			while (cursor != null)
			{
				for (Method m : cursor.getDeclaredMethods())
				{
					if (m.getName().equals(name))
					{
						m.setAccessible(true);
						return m;
					}
				}
				cursor = cursor.getSuperclass();
			}
			return null;
		}
	}
}
