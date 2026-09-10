/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.hooks;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Typed model of {@code hooks.json} (format openosrs-hooks/2), as emitted by
 * the revision generator. One file per revision; every structural fact inside is
 * verified offline before emission and re-verified against the loaded client
 * at bind time ({@link net.openosrs.api.dispatch.PacketDispatcher}).
 */
@Data
public class HooksFile
{
	@SerializedName("format")
	private String format;

	@SerializedName("revision")
	private Integer revision;

	@SerializedName("jarSha256")
	private String jarSha256;

	@SerializedName("classCount")
	private int classCount;

	@SerializedName("totalClassCount")
	private int totalClassCount;

	@SerializedName("families")
	private Families families;

	@SerializedName("sendPath")
	private SendPath sendPath;

	/** Buffer read/write methods classified by byte-width invariants. */
	@SerializedName("bufferMethods")
	private Map<String, BufferMethodDef> bufferMethods;

	@SerializedName("packetCount")
	private int packetCount;

	@SerializedName("serverPacketCount")
	private int serverPacketCount;

	/** Explicit trusted/quarantined/no-layout partition emitted by the dumper. */
	@SerializedName("layoutCoverage")
	private LayoutCoverage layoutCoverage;

	/** Per-build obfuscation constants used by provenance trace decoding. */
	@SerializedName("trace")
	private TraceMetadata trace;

	@SerializedName("packets")
	private List<PacketDef> packets = new ArrayList<>();

	@SerializedName("serverPackets")
	private List<ServerPacketDef> serverPackets = new ArrayList<>();

	@Data
	public static class Families
	{
		private String clientPacket;
		private String packetBufferNode;
		private String buffer;
		private String bufferPayloadField;
		private String bufferOffsetField;
		private List<String> bufferSubclasses;
		private String serverPacket;
		private String serverPacketHolder;
		private List<String> writersReferencingBoth;
		private String isaac;
	}

	@Data
	public static class SendPath
	{
		private String factoryOwner;
		private List<String> factoryMethods;
		private String writerClass;
		private String addNodeMethod;
		private String addNodeDescriptor;
		private boolean addNodeStatic;
		private Long garbageConstant;
		private String writerCipherField;
		private String clientWriterField;
		private String nodeBufferField;
	}

	@Data
	public static class BufferMethodDef
	{
		private String desc;
		/** Declaring buffer-family class of the method. */
		private String owner;
		/** Static byte width "W#" (BASTORE count) written per call. */
		private String width;
		private String read;
		@SerializedName("static")
		private boolean staticMethod;
	}

	@Data
	public static class TraceMetadata
	{
		private String idField;
		private String declaredField;
		private Integer idMultiplier;
		private Integer declaredMultiplier;
		private Integer offsetMultiplier;
	}

	@Data
	public static class LayoutCoverage
	{
		private int trustedCount;
		private List<Integer> trustedIds = new ArrayList<>();
		private List<Integer> quarantinedFixedIds = new ArrayList<>();
		private List<Integer> quarantinedVariableIds = new ArrayList<>();
		private List<Integer> noLayoutIds = new ArrayList<>();
	}

	@Data
	public static class PacketDef
	{
		private int id;
		/** Semantic name seeded from a verified reference table, if any. */
		private String name;
		private List<String> fields;
		private Integer length;
		/** Ordered payload ops: m = obf buffer method, w = "W#" width. */
		private List<WriteOp> writes;
		private String layoutSource;
	}

	@Data
	public static class ServerPacketDef
	{
		private int id;
		private List<String> fields;
		private Integer length;
	}

	@Data
	public static class WriteOp
	{
		private String m;
		private String d;
		private String w;
		/** Declaring class. Required to prevent same-name overload confusion. */
		private String owner;
		/** Invocation kind; false means virtual/interface, true means static. */
		@SerializedName("static")
		private boolean staticMethod;
	}

	public static HooksFile load()
	{
		InputStream input = HooksFile.class.getResourceAsStream("/hooks.json");
		if (input == null)
		{
			throw new IllegalStateException("hooks.json not on classpath");
		}
		try (InputStream in = input;
			InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			HooksFile file = new Gson().fromJson(reader, HooksFile.class);
			if (file == null)
			{
				throw new IllegalStateException("hooks.json is empty");
			}
			return file;
		}
		catch (java.io.IOException e)
		{
			throw new IllegalStateException("cannot close hooks.json", e);
		}
	}

	/** Packet definition owning the given obf field name (e.g. "af"). */
	public PacketDef packetByField(String fieldName)
	{
		if (fieldName == null)
		{
			return null;
		}
		for (PacketDef p : packets)
		{
			if (p.fields != null && p.fields.contains(fieldName))
			{
				return p;
			}
		}
		return null;
	}

	/** Packet definition by seeded semantic name (e.g. "RESUME_COUNTDIALOG"). */
	public PacketDef packetByName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (PacketDef p : packets)
		{
			if (name.equals(p.getName()))
			{
				return p;
			}
		}
		return null;
	}

	/** Packet definition by opcode. */
	public PacketDef packetById(int id)
	{
		for (PacketDef p : packets)
		{
			if (p.id == id)
			{
				return p;
			}
		}
		return null;
	}

	public void validate()
	{
		if (!"openosrs-hooks/2".equals(format))
		{
			throw new IllegalStateException("unsupported hooks format: " + format);
		}
		if (jarSha256 == null || !jarSha256.matches("[0-9a-fA-F]{64}"))
		{
			throw new IllegalStateException("hooks has invalid jarSha256");
		}
		if (revision == null || revision <= 0 || classCount <= 0 || totalClassCount < classCount
			|| packetCount <= 0 || serverPacketCount <= 0
			|| families == null || sendPath == null || packets == null || packets.isEmpty()
			|| serverPackets == null || serverPackets.isEmpty())
		{
			throw new IllegalStateException("incomplete hooks file");
		}
		Families f = families;
		require(f.clientPacket, "families.clientPacket");
		require(f.packetBufferNode, "families.packetBufferNode");
		require(f.buffer, "families.buffer");
		require(f.bufferPayloadField, "families.bufferPayloadField");
		require(f.bufferOffsetField, "families.bufferOffsetField");
		require(f.isaac, "families.isaac");
		requireList(f.bufferSubclasses, "families.bufferSubclasses");
		requireList(f.writersReferencingBoth, "families.writersReferencingBoth");
		SendPath s = sendPath;
		require(s.factoryOwner, "sendPath.factoryOwner");
		if (s.factoryMethods == null || s.factoryMethods.isEmpty())
		{
			throw new IllegalStateException("missing sendPath.factoryMethods");
		}
		require(s.writerClass, "sendPath.writerClass");
		require(s.addNodeMethod, "sendPath.addNodeMethod");
		if (s.garbageConstant == null)
		{
			throw new IllegalStateException("missing sendPath.garbageConstant");
		}
		require(s.writerCipherField, "sendPath.writerCipherField");
		require(s.clientWriterField, "sendPath.clientWriterField");
		require(s.nodeBufferField, "sendPath.nodeBufferField");
		if (s.factoryMethods == null || s.factoryMethods.stream().anyMatch(v -> !validMethodKey(v)))
		{
			throw new IllegalStateException("invalid sendPath.factoryMethods");
		}
		if (trace != null && (trace.idField == null || trace.idField.isBlank()
			|| trace.declaredField == null || trace.declaredField.isBlank()
			|| !validMultiplier(trace.idMultiplier) || !validMultiplier(trace.declaredMultiplier)
			|| !validMultiplier(trace.offsetMultiplier)))
		{
			throw new IllegalStateException("invalid trace metadata");
		}
		if (!validMethodKey(s.addNodeMethod + (s.addNodeDescriptor == null ? "" : s.addNodeDescriptor))
			|| !validDescriptor(s.addNodeDescriptor))
		{
			throw new IllegalStateException("invalid sendPath.addNodeMethod");
		}
		if (bufferMethods == null || bufferMethods.isEmpty())
		{
			throw new IllegalStateException("missing bufferMethods");
		}
		for (Map.Entry<String, BufferMethodDef> entry : bufferMethods.entrySet())
		{
			BufferMethodDef method = entry.getValue();
			if (method == null || !validMethodKey(entry.getKey()) ||
				!entry.getKey().endsWith(method.desc) ||
				!validDescriptor(method.desc) || method.owner == null || method.owner.isEmpty() ||
				!validWidth(method.width) || method.read == null)
			{
				throw new IllegalStateException("invalid buffer method: " + entry.getKey());
			}
		}
		if (packets.size() != packetCount || serverPackets.size() != serverPacketCount)
		{
			throw new IllegalStateException("packet count mismatch: c2s=" + packets.size()
				+ "/" + packetCount + " s2c=" + serverPackets.size() + "/" + serverPacketCount);
		}
		validatePacketRows(packets, packetCount, "packets", true);
		validateServerRows(serverPackets, serverPacketCount);
		validateLayoutCoverage();
	}

	private void validateLayoutCoverage()
	{
		if (layoutCoverage == null)
		{
			return; // compatible with hooks emitted before coverage metadata existed
		}
		if (layoutCoverage.trustedCount < 0 || layoutCoverage.trustedIds == null
			|| layoutCoverage.quarantinedFixedIds == null
			|| layoutCoverage.quarantinedVariableIds == null || layoutCoverage.noLayoutIds == null)
		{
			throw new IllegalStateException("invalid layoutCoverage");
		}
		Set<Integer> partition = new HashSet<>();
		for (List<Integer> ids : List.of(layoutCoverage.trustedIds,
			layoutCoverage.quarantinedFixedIds, layoutCoverage.quarantinedVariableIds,
			layoutCoverage.noLayoutIds))
		{
			for (Integer id : ids)
			{
				if (id == null || id < 0 || id >= packetCount || !partition.add(id))
				{
					throw new IllegalStateException("layoutCoverage partition is invalid");
				}
			}
		}
		long emitted = packets.stream().filter(p -> p.writes != null).count();
		if (layoutCoverage.trustedCount != layoutCoverage.trustedIds.size()
			|| emitted != layoutCoverage.trustedCount || partition.size() != packetCount)
		{
			throw new IllegalStateException("layoutCoverage counts do not partition C2S packets");
		}
		Set<Integer> trusted = new HashSet<>(layoutCoverage.trustedIds);
		for (PacketDef packet : packets)
		{
			if (trusted.contains(packet.id) != (packet.writes != null))
			{
				throw new IllegalStateException("layoutCoverage trusted/write mismatch for id=" + packet.id);
			}
		}
	}

	private void validatePacketRows(List<PacketDef> rows, int expected, String label,
		boolean requireWrites)
	{
		Set<Integer> ids = new HashSet<>();
		for (PacketDef p : rows)
		{
			if (p == null || p.id < 0 || p.id >= expected || !ids.add(p.id)
				|| p.fields == null || p.fields.size() != 1 || p.fields.get(0) == null
				|| p.length == null || p.length < -2
				|| (requireWrites && p.layoutSource != null && p.writes == null))
			{
				throw new IllegalStateException("invalid " + label + " row");
			}
			if (p.writes != null)
			{
				if (p.writes.isEmpty() && (p.length == null || p.length != 0
					|| !"derived-zero-verified".equals(p.layoutSource)))
				{
					throw new IllegalStateException("empty writes for packet id=" + p.id);
				}
				int width = 0;
				for (WriteOp op : p.writes)
				{
					if (op == null || !validMethodKey(op.m + op.d) || !validDescriptor(op.d)
						|| !validWidth(op.w) || "W0".equals(op.w)
						|| op.owner == null || op.owner.isEmpty())
					{
						throw new IllegalStateException("invalid write op for packet id=" + p.id);
					}
					width += Integer.parseInt(op.w.substring(1));
				}
				if (p.length >= 0 && width != p.length)
				{
					throw new IllegalStateException("write width " + width
						+ " does not match packet length " + p.length + " for id=" + p.id);
				}
			}

		}
		if (ids.size() != expected)
		{
			throw new IllegalStateException(label + " id coverage is " + ids.size() + "/" + expected);
		}
	}

	private void validateServerRows(List<ServerPacketDef> rows, int expected)
	{
		Set<Integer> ids = new HashSet<>();
		for (ServerPacketDef p : rows)
		{
			if (p == null || p.id < 0 || p.id >= expected || !ids.add(p.id)
				|| p.fields == null || p.fields.size() != 1 || p.length == null || p.length < -2)
			{
				throw new IllegalStateException("invalid server packet row");
			}
		}
		if (ids.size() != expected)
		{
			throw new IllegalStateException("server packet id coverage is " + ids.size() + "/" + expected);
		}
	}

	private static boolean validMethodKey(String key)
	{
		return key != null && key.indexOf('(') > 0 && key.endsWith(")V") ||
			(key != null && key.indexOf('(') > 0 && key.indexOf(')') > key.indexOf('('));
	}

	private static boolean validDescriptor(String desc)
	{
		return desc != null && desc.startsWith("(") && desc.indexOf(')') > 0;
	}

	private static boolean validMultiplier(Integer multiplier)
	{
		return multiplier != null && (multiplier & 1) != 0;
	}

	private static boolean validWidth(String width)
	{
		if (width == null || !width.startsWith("W")) return false;
		try { return Integer.parseInt(width.substring(1)) >= 0; }
		catch (NumberFormatException e) { return false; }
	}

	private static void requireList(List<String> value, String name)
	{
		if (value == null || value.isEmpty() || value.stream().anyMatch(v -> v == null || v.trim().isEmpty()))
		{
			throw new IllegalStateException("missing hooks field: " + name);
		}
	}

	private static void require(String value, String name)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalStateException("missing hooks field: " + name);
		}
	}
}
