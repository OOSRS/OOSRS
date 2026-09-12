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
    // Private detached snapshot. Never exposed or mutated after publication.
    private final HooksFile metadata;
    private final String metadataFailure;
    private volatile Binding scratchBinding;
	private volatile Binding binding;
	private volatile boolean disabled;

	@Inject
	public PacketDispatcher(Client client, Hooks hooks)
	{
		this.client = client;
		this.hooks = hooks;
        HooksFile snapshot = null;
        String failure = null;
        try
        {
            HooksFile supplied = hooks.file();
            if (supplied != null)
            {
                snapshot = supplied.copy();
                snapshot.validate();
            }
            else failure = "validated hooks are unavailable";
        }
        catch (RuntimeException ex)
        {
            snapshot = null;
            failure = "invalid hooks metadata";
        }
        metadata = snapshot;
        metadataFailure = failure;
	}

	/** True only when hooks loaded AND bind-time verification succeeded. */
	public boolean available()
	{
		if (!client.isClientThread() || disabled || metadata == null || !hooks.isPacketTierAvailable())
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
		if (!client.isClientThread() || disabled || !hooks.isPacketTierAvailable()) return false;
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
        if (!client.isClientThread()) return "REJECTED: client thread required";
        if (metadata == null || !hooks.isPacketTierAvailable()) return "DISABLED: validated hooks are unavailable";
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

    /** Replay only payload writes on a fresh byte-array-backed buffer. No live binding. */
    public String dryRunById(int id, Object... values)
    {
        if (metadata == null) return "DISABLED: " + metadataFailure;
        HooksFile.PacketDef packet = metadata.packetById(id);
        if (packet == null) return "no packet with id " + id;
        if (packet.getWrites() == null) return "QUARANTINE id=" + id + ": no verified layout";
        try
        {
            Binding b = scratchBinding();
            Object scratch = b.newScratchBuffer();
            List<Method> ops = b.layoutMethods(packet);
            values = values == null ? new Object[0] : values;
            if (values.length != 0 && values.length != ops.size())
                return "REJECTED id=" + id + ": payload arity mismatch";
            int before = b.offset(scratch);
            for (int i = 0; i < ops.size(); i++)
            {
                Method op = ops.get(i);
                Object value = values.length == 0
                    ? (java.util.Arrays.asList(op.getParameterTypes()).contains(String.class) ? "" : 0)
                    : values[i];
                if (!b.acceptsValue(op, value)) return "REJECTED id=" + id + ": invalid payload value";
                if (!Modifier.isStatic(op.getModifiers()) && !op.getDeclaringClass().isInstance(scratch))
                    return "INCONCLUSIVE id=" + id + ": operation requires packet-buffer state";
                op.invoke(Modifier.isStatic(op.getModifiers()) ? null : scratch,
                    b.fillArgs(op.getParameterTypes(), value, scratch));
            }
            int delta = b.offset(scratch) - before;
            if (delta < 0 || delta > b.payload(scratch).length)
                return "FAILED id=" + id + ": decoded offset outside scratch buffer";
            if (packet.getLength() < 0)
                return "INCONCLUSIVE id=" + id + " bytes=" + delta + ": variable framing requires a packet-specific fixture";
            if (delta != packet.getLength())
                return "FAILED id=" + id + ": payload bytes=" + delta + " declaredLen=" + packet.getLength();
            return "OK id=" + id + " bytes=" + delta + " (isolated scratch payload only)";
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ex)
        {
            return "FAILED id=" + id + ": scratch " + ex.getClass().getSimpleName();
        }
    }

	/**
	 * Dry-run each mapped payload without login or live writer/cipher access.
	 * Variable framing is reported separately from fixed payload checks.
	 */
	public String dryRunAll()
    {
        HooksFile snapshot = metadata;
        if (snapshot == null || snapshot.getPackets() == null)
        {
            return "DISABLED: validated hooks are unavailable";
        }
		int ok = 0;
		int inconclusive = 0;
		int noLayout = 0;
		Map<String, Integer> failures = new java.util.TreeMap<>();
		for (HooksFile.PacketDef p : snapshot.getPackets())
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
			.append(" failed=").append(snapshot.getPackets().size() - ok - inconclusive - noLayout);
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
		return dispatch(metadata == null ? null : metadata.packetByName(packetName), values);
	}

	/** Send a packet by opcode when a revision table has no semantic name. */
	public boolean sendById(int id, Object... values)
	{
		return dispatch(metadata == null ? null : metadata.packetById(id), values);
	}

	private boolean dispatch(HooksFile.PacketDef packet, Object[] values)
	{
		values = values == null ? new Object[0] : values.clone();
		if (!client.isClientThread())
		{
			log.warn("packet dispatch must run on the client thread");
			return false;
		}
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) return false;
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
		boolean factoryInvoked = false;
		try
		{
			Binding b = binding();
            byte[] payload = b.encodePayload(packet, values);
            int size = payload.length;
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
            factoryInvoked = true;
			Object node = b.factory.invoke(null, packetInstance, cipher);
			if (!b.nodeClass.isInstance(node))
			{
				throw new IllegalStateException("factory returned no node");
			}
			Object buffer = b.nodeBuffer.get(node);
            int start = b.offset(buffer);
            byte[] destination = b.payload(buffer);
            if (start < 0 || start > destination.length - size)
            {
                disabled = true;
                throw new IllegalStateException("native packet buffer capacity mismatch");
            }
            System.arraycopy(payload, 0, destination, start, size);
            b.bufferOffset.setInt(buffer, b.bufferOffset.getInt(buffer) + size * b.offsetIncrement);
			b.queue.invoke(b.writer, node, b.garbage);
			log.debug("packet id {} enqueued", packet.getId());
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
            if (factoryInvoked) disabled = true;
			log.warn("packet dispatch failed for id {}: {}", packet.getId(), e.toString());
			return false;
		}
	}

    /** Framing is separate from the number of stores made by a buffer method. */
    static boolean validPayloadSize(int declared, byte[] bytes, int size)
    {
        if (size < 0 || size > bytes.length) return false;
        if (declared >= 0) return size == declared;
        if (declared == -1)
            return size >= 1 && size <= 256 && (bytes[0] & 255) == size - 1;
        // Explicit API size budget, below the pinned native 10000-byte allocation.
        return declared == -2 && size >= 2 && size <= 8192
            && (((bytes[0] & 255) << 8) | (bytes[1] & 255)) == size - 2;
    }

	/*
	 * Argument filling moved to Binding.fillArgs — needs buffer-family types
	 * for sub-buffer params.
	 */
	// ------------------------------------------------------------------
	// binding
	// ------------------------------------------------------------------

	private Binding scratchBinding() throws ReflectiveOperationException
    {
        Binding current = scratchBinding;
        if (current != null) return current;
        synchronized (this)
        {
            if (scratchBinding == null) scratchBinding = new Binding(client, metadata, false);
            return scratchBinding;
        }
    }

    private Binding binding() throws ReflectiveOperationException
    {
        if (!client.isClientThread()) throw new IllegalStateException("client thread required");
        if (metadata == null || !hooks.isPacketTierAvailable()) throw new IllegalStateException("hooks unavailable or stale revision");
		Binding current = binding;
		if (current != null)
		{
            if (current.writerField.get(Modifier.isStatic(current.writerField.getModifiers()) ? null : client) == current.writer)
                return current;
            binding = null; // A replacement writer belongs to a new binding, never the old session cache.
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
					binding = new Binding(client, metadata, true);
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
        private final Field writerField;
		private final Field cipherField;
		private final Method factory;
		private final Method queue;
		private final int garbage;
		private final Field nodeBuffer;
		private final Field bufferOffset;
        private final Field bufferPayload;
        private final int offsetMultiplier;
        private final int offsetIncrement;
		private final Map<String, Field> packetFields;
		private final Map<Integer, List<Method>> layoutCache;

		private Binding(Client client, HooksFile file, boolean live) throws ReflectiveOperationException
		{
			ClassLoader loader = client.getClass().getClassLoader();
			HooksFile.Families fam = file.getFamilies();
			HooksFile.SendPath sp = file.getSendPath();

			packetClass = loader.loadClass(fam.getClientPacket());
			nodeClass = loader.loadClass(fam.getPacketBufferNode());
			bufferClass = loader.loadClass(fam.getBuffer());
			Class<?> isaacClass = live ? loader.loadClass(fam.getIsaac()) : null;

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

            // Scratch mode resolves buffer metadata only. It never reads these live fields.
            if (live)
            {
                writerField = findField(client.getClass(), sp.getClientWriterField());
                writer = writerField.get(Modifier.isStatic(writerField.getModifiers()) ? null : client);
                if (writer == null || !sp.getWriterClass().equals(writer.getClass().getName()))
                    throw new IllegalStateException("packet writer unavailable or incompatible");
                cipherField = findField(writer.getClass(), sp.getWriterCipherField());
                if (Modifier.isStatic(cipherField.getModifiers()) || cipherField.getType() != isaacClass)
                    throw new IllegalStateException("cipher field descriptor mismatch");
                factory = resolveFactory(loader, file);
                queue = findMethodByDesc(writer.getClass(), sp.getAddNodeMethod(), sp.getAddNodeDescriptor());
                if (queue == null || Modifier.isStatic(queue.getModifiers()) != sp.isAddNodeStatic()
                    || sp.isAddNodeStatic() || queue.getReturnType() != void.class)
                    throw new IllegalStateException("queue descriptor/staticness mismatch");
                long constant = sp.getGarbageConstant();
                if (constant < Integer.MIN_VALUE || constant > Integer.MAX_VALUE)
                    throw new IllegalStateException("queue constant outside int range");
                garbage = (int) constant;
            }
            else
            {
                writer = null;
                writerField = null;
                cipherField = null;
                factory = null;
                queue = null;
                garbage = 0;
            }
            nodeBuffer = findField(nodeClass, sp.getNodeBufferField());
            bufferOffset = findField(bufferClass, fam.getBufferOffsetField());
            bufferPayload = findField(bufferClass, fam.getBufferPayloadField());
            if (file.getTrace() == null) throw new IllegalStateException("offset decoder metadata missing");
            // Trace metadata stores the encoded increment, not its modular inverse.
            offsetIncrement = file.getTrace().getOffsetMultiplier();
            offsetMultiplier = java.math.BigInteger.valueOf(Integer.toUnsignedLong(offsetIncrement))
                .modInverse(java.math.BigInteger.ONE.shiftLeft(32)).intValue();
            if (Modifier.isStatic(bufferPayload.getModifiers()) || bufferPayload.getType() != byte[].class
                || Modifier.isStatic(bufferOffset.getModifiers()) || Modifier.isStatic(nodeBuffer.getModifiers()))
                throw new IllegalStateException("buffer field descriptor/staticness mismatch");

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

            Map<String, Field> fields = new HashMap<>();
            Map<Integer, List<Method>> layouts = new HashMap<>();
            for (HooksFile.PacketDef p : file.getPackets())
            {
                Field field = findField(packetClass, p.getFields().get(0));
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != packetClass)
                    throw new IllegalStateException("invalid packet field descriptor");
                fields.put(p.getFields().get(0), field);
                if (p.getWrites() == null) continue;
                List<Method> methods = new ArrayList<>();
                for (HooksFile.WriteOp op : p.getWrites())
                {
                    Method method = resolveBufferMethod(op);
                    if (method == null || method.getReturnType() != void.class)
                        throw new IllegalStateException("missing payload method for id=" + p.getId());
                    methods.add(method);
                }
                layouts.put(p.getId(), List.copyOf(methods));
            }
            packetFields = Map.copyOf(fields);
            layoutCache = Map.copyOf(layouts);
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
					Method m = findMethodByDesc(owner, name, spec.substring(spec.indexOf('(')));
                    if (m == null || m.getDeclaringClass() != owner
                        || !java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[]{packetType, cipherType})) continue;
                    m.setAccessible(true);
					if (Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(nodeType))
					{
						return m;
					}
				}
				catch (RuntimeException ex)
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
					else if ((type != int.class && type != long.class)
                        || !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                        || (type == int.class && (((Number) value).longValue() < Integer.MIN_VALUE
                            || ((Number) value).longValue() > Integer.MAX_VALUE)))
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

        /** The byte[] constructor uses our array, never the game byte-array pool. */
        Object newScratchBuffer() throws ReflectiveOperationException
        {
            return newScratchBuffer(8192);
        }

        Object newScratchBuffer(int capacity) throws ReflectiveOperationException
        {
            java.lang.reflect.Constructor<?> constructor = bufferClass.getDeclaredConstructor(byte[].class);
            constructor.setAccessible(true);
            return constructor.newInstance((Object) new byte[capacity]);
        }

        /** Shared by live dispatch and offline fixtures; never consumes an ISAAC value. */
        byte[] encodePayload(HooksFile.PacketDef packet, Object[] values) throws ReflectiveOperationException
        {
            List<Method> ops = layoutMethods(packet);
            if (values.length != ops.size()) throw new IllegalArgumentException("payload arity mismatch");
            Object scratch = newScratchBuffer(packet.getLength() >= 0 ? packet.getLength() : 8192);
            for (int i = 0; i < ops.size(); i++)
            {
                Method op = ops.get(i);
                if (!acceptsValue(op, values[i])) throw new IllegalArgumentException("unsupported payload value");
                if (!Modifier.isStatic(op.getModifiers()) && !op.getDeclaringClass().isInstance(scratch))
                    throw new IllegalArgumentException("operation requires packet-buffer state");
                op.invoke(Modifier.isStatic(op.getModifiers()) ? null : scratch,
                    fillArgs(op.getParameterTypes(), values[i], scratch));
            }
            int size = offset(scratch);
            byte[] bytes = payload(scratch);
            if (!validPayloadSize(packet.getLength(), bytes, size))
                throw new IllegalArgumentException("payload length or variable framing mismatch");
            return java.util.Arrays.copyOf(bytes, size);
        }

        int offset(Object buffer) throws IllegalAccessException
        {
            return bufferOffset.getInt(buffer) * offsetMultiplier;
        }

        byte[] payload(Object buffer) throws IllegalAccessException
        {
            return (byte[]) bufferPayload.get(buffer);
        }

        Object cipher() throws ReflectiveOperationException
        {
            return cipherField.get(writer);
        }

        Field packetField(String name)
        {
            Field field = packetFields.get(name);
            if (field == null) throw new IllegalArgumentException("unknown packet field");
            return field;
        }

        List<Method> layoutMethods(HooksFile.PacketDef packet)
        {
            List<Method> methods = layoutCache.get(packet.getId());
            if (methods == null) throw new IllegalArgumentException("unverified payload layout");
            return methods;
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

	}
}
