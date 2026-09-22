package net.openosrs.api.identity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Four targeted, in-memory method overrides bound to a stock-launch capture. */
public final class GamepackIdentityPatch
{
	private static final Logger log = LoggerFactory.getLogger(GamepackIdentityPatch.class);
	private static final String HELPER = Type.getInternalName(DeviceIdentity.class);
	private static final String STRING = "Ljava/lang/String;";
	private static final Pattern STACK = Pattern.compile(
		"client\\d+[A-Za-z0-9_$+]+\\nclient\\d+[A-Za-z0-9_$+]+\\n"
			+ "nrc\\.RuneLite\\d+start\\nnrc\\.RuneLite\\d+main\\n"
			+ "nrl\\.ReflectionLa\\+\\d+lambda\\$launc\\+\\n"
			+ "jl\\.Thread(?:Unknown [A-Za-z ]*\\+|\\d+run)");

	private GamepackIdentityPatch()
	{
	}

	public static Map<String, byte[]> prepare(JarFile jar) throws IOException
	{
		return prepare(jar, List.of());
	}

	/**
	 * Prepare every in-memory class override for this game artifact.
	 *
	 * @param holderSetters {@code owner.setter} names of constant-dynamic credential
	 *                      holders that need a getter; see {@link HolderAccessors}
	 */
	public static Map<String, byte[]> prepare(JarFile jar, Collection<String> holderSetters) throws IOException
	{
		boolean identity = Boolean.parseBoolean(System.getProperty("oos.identity.enabled", "true"));
		if (!identity)
		{
			log.info("Gamepack identity overrides disabled");
		}
		if (!identity && holderSetters.isEmpty())
		{
			return Map.of();
		}
		try (InputStream capture = identity ? GamepackIdentityPatch.class.getResourceAsStream("/identity-callstack.json") : null)
		{
			return prepare(jar, identity, capture, holderSetters);
		}
	}

	private static Map<String, byte[]> prepare(JarFile jar, boolean identity, InputStream capture,
		Collection<String> holderSetters) throws IOException
	{
		Map<String, byte[]> originals = new LinkedHashMap<>();
		Map<String, ClassNode> classes = new LinkedHashMap<>();
		for (JarEntry entry : java.util.Collections.list(jar.entries()))
		{
			if (entry.getName().endsWith(".class"))
			{
				try (InputStream input = jar.getInputStream(entry))
				{
					byte[] bytes = input.readAllBytes();
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, 0);
					classes.put(node.name, node);
					originals.put(node.name, bytes);
				}
			}
		}

		Set<ClassNode> changed = new LinkedHashSet<>();
		if (identity)
		{
			changed.addAll(applyIdentity(jar, classes, capture));
		}
		for (String location : holderSetters)
		{
			int split = location.lastIndexOf('.');
			ClassNode owner = split < 0 ? null : classes.get(location.substring(0, split));
			require(owner != null, "Missing credential holder owner for " + location);
			HolderAccessors.ensureGetter(owner, location.substring(split + 1));
			changed.add(owner);
		}

		Map<String, byte[]> patched = new LinkedHashMap<>();
		for (ClassNode node : changed)
		{
			HolderAccessors.requireDistinctDynamics(originals.get(node.name), node.name);
			// Existing method frames stay untouched; replacements and holder getters
			// are straight-line methods and need only their maximum stack recomputed.
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			patched.put(node.name.replace('/', '.'), writer.toByteArray());
		}
		return patched;
	}

	private static List<ClassNode> applyIdentity(JarFile jar, Map<String, ClassNode> classes, InputStream capture) throws IOException
	{
		require(capture != null, "No stock RuneLite call-stack capture; run oos.sh callstack");
		JsonObject reference = new JsonParser().parse(new InputStreamReader(capture, StandardCharsets.UTF_8)).getAsJsonObject();
		String stack = reference.get("stack").getAsString();
		String jarHash = DeviceIdentity.digest(Files.readAllBytes(Path.of(jar.getName())));
		require("captured".equals(reference.get("status").getAsString())
			&& "stock-runelite-reflect-memory".equals(reference.get("source").getAsString())
			&& reference.get("distinctStacks").getAsInt() == 1, "Unverified call-stack reference");
		require(jarHash.equals(reference.get("injectedClientSha256").getAsString()),
			"Call-stack reference belongs to another injected client; capture the new version first");
		require(STACK.matcher(stack).matches()
			&& DeviceIdentity.digest(stack.getBytes(StandardCharsets.UTF_8)).equals(reference.get("stackSha256").getAsString()),
			"Invalid call-stack capture contents");

		ClassNode client = classes.get("client");
		require(client != null, "Game client class is missing");
		List<MethodNode> checks = new ArrayList<>();
		List<MethodNode> combiners = new ArrayList<>();
		Map<String, AbstractInsnNode> characterReads = new LinkedHashMap<>();
		List<FieldInsnNode> usernameFields = new ArrayList<>();
		for (MethodNode method : client.methods)
		{
			if (method.desc.equals("(J)" + STRING) && isStatic(method)
				&& calls(method, "java/lang/RuntimeException", "getStackTrace"))
			{
				checks.add(method);
			}
			if (method.desc.equals("()" + STRING) && isStatic(method)
				&& stringFields(method, Opcodes.GETSTATIC, client.name).size() == 2)
			{
				combiners.add(method);
			}
			if (method.name.equals("getUsername") && method.desc.equals("()" + STRING))
			{
				usernameFields.addAll(stringFields(method, Opcodes.GETSTATIC, null));
			}
			for (AbstractInsnNode instruction : method.instructions)
			{
				if (instruction instanceof LdcInsnNode && "JX_CHARACTER_ID".equals(((LdcInsnNode) instruction).cst))
				{
					// Character ID's property read is immediately followed by its store,
					// independent of obfuscated names: a static String field, or a
					// constant-dynamic holder setter that needs an emitted getter.
					AbstractInsnNode cursor = instruction.getNext();
					for (int distance = 0; cursor != null && distance < 8; distance++, cursor = cursor.getNext())
					{
						if (cursor instanceof LdcInsnNode)
						{
							break;
						}
						if (cursor.getOpcode() == Opcodes.PUTSTATIC && cursor instanceof FieldInsnNode)
						{
							FieldInsnNode field = (FieldInsnNode) cursor;
							if (field.desc.equals(STRING))
							{
								characterReads.put(field.owner + "." + field.name,
									new FieldInsnNode(Opcodes.GETSTATIC, field.owner, field.name, field.desc));
							}
							break;
						}
						if (cursor.getOpcode() == Opcodes.INVOKESTATIC && cursor instanceof MethodInsnNode
							&& "(Ljava/lang/String;)V".equals(((MethodInsnNode) cursor).desc))
						{
							MethodInsnNode call = (MethodInsnNode) cursor;
							ClassNode owner = classes.get(call.owner);
							MethodNode setter = owner == null ? null : owner.methods.stream()
								.filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().orElse(null);
							if (setter != null && HolderAccessors.holder(setter) != null)
							{
								// The getter is emitted after this scan: adding it now would
								// modify the method list being iterated.
								characterReads.put("holder:" + owner.name + "." + setter.name,
									new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name,
										HolderAccessors.getterName(setter.name), "()" + STRING, false));
							}
							break;
						}
					}
				}
			}
		}
		MethodNode check = only(checks, "call-stack checker");
		MethodNode combiner = only(combiners, "packed-stack combiner");
		AbstractInsnNode characterId = only(new ArrayList<>(characterReads.values()), "character ID store");
		if (characterId instanceof MethodInsnNode)
		{
			MethodInsnNode read = (MethodInsnNode) characterId;
			HolderAccessors.ensureGetter(classes.get(read.owner),
				read.name.substring(0, read.name.length() - HolderAccessors.GETTER_SUFFIX.length()));
		}
		FieldInsnNode username = uniqueField(usernameFields, "username");
		List<FieldInsnNode> packedFields = stringFields(combiner, Opcodes.GETSTATIC, client.name);
		List<MethodNode> packers = new ArrayList<>();
		for (FieldInsnNode field : packedFields)
		{
			List<MethodNode> writers = new ArrayList<>();
			for (MethodNode method : client.methods)
			{
				if (method.desc.equals("()V") && calls(method, "java/lang/System", "currentTimeMillis")
					&& stringFields(method, Opcodes.PUTSTATIC, client.name).stream().anyMatch(f -> f.name.equals(field.name)))
				{
					writers.add(method);
				}
			}
			packers.add(only(writers, "packed-stack writer " + field.name));
		}
		require(packers.get(0) != packers.get(1), "Packed-stack writers overlap");
		require(stack.split("\n")[0].matches("client\\d+" + Pattern.quote(check.name)),
			"Captured stack does not name this gamepack checker");
		require(packers.stream().anyMatch(p -> stack.split("\n")[1].matches("client\\d+" + Pattern.quote(p.name))),
			"Captured stack does not name this gamepack initialization method");

		// Identify the platform class by its hardware-query strings, then select
		// the live (int,int)->String entrypoint used by the current script path.
		List<ClassNode> platformClasses = new ArrayList<>();
		for (ClassNode node : classes.values())
		{
			if (node.methods.stream().anyMatch(m -> contains(m, "cat /etc/machine-id") && contains(m, "wmic csproduct get UUID")))
			{
				platformClasses.add(node);
			}
		}
		ClassNode platform = only(platformClasses, "platform identity class");
		List<MethodNode> uuidMethods = new ArrayList<>();
		for (MethodNode method : platform.methods)
		{
			if (!isStatic(method) && method.desc.equals("(II)" + STRING)
				&& classes.values().stream().anyMatch(c -> c.methods.stream().anyMatch(m -> calls(m, platform.name, method.name, method.desc))))
			{
				uuidMethods.add(method);
			}
		}
		MethodNode uuid = only(uuidMethods, "live platform UUID method");
		if (characterId instanceof FieldInsnNode)
		{
			validateFieldAccess(classes, (FieldInsnNode) characterId, platform.name);
		}
		validateFieldAccess(classes, username, platform.name);

		replace(check);
		check.instructions.add(new LdcInsnNode(stack));
		check.instructions.add(new InsnNode(Opcodes.ARETURN));
		for (int i = 0; i < 2; i++)
		{
			MethodNode packer = packers.get(i);
			FieldInsnNode field = packedFields.get(i);
			replace(packer);
			packer.instructions.add(new InsnNode(i == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
			packer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "packedStack", "(I)" + STRING, false));
			packer.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, field.owner, field.name, field.desc));
			packer.instructions.add(new InsnNode(Opcodes.RETURN));
		}
		replace(uuid);
		uuid.instructions.add(characterId.clone(java.util.Collections.emptyMap()));
		uuid.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, username.owner, username.name, STRING));
		uuid.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolve", "(" + STRING + STRING + ")" + STRING, false));
		uuid.instructions.add(new InsnNode(Opcodes.ARETURN));

		log.info("Identity overrides ready: RuneLite {}, rev {}, client={}, stack={}, UUID={}.{}{}",
			reference.get("runeliteVersion").getAsString(), reference.get("revision").getAsInt(), jarHash,
			reference.get("stackSha256").getAsString(), platform.name, uuid.name, uuid.desc);
		List<ClassNode> touched = new ArrayList<>(List.of(client, platform));
		if (characterId instanceof MethodInsnNode)
		{
			ClassNode holderOwner = classes.get(((MethodInsnNode) characterId).owner);
			if (!touched.contains(holderOwner))
			{
				touched.add(holderOwner);
			}
		}
		return touched;
	}

	private static void validateFieldAccess(Map<String, ClassNode> classes, FieldInsnNode field, String caller)
	{
		ClassNode owner = classes.get(field.owner);
		require(owner != null, "Missing identity field owner");
		FieldNode target = only(owner.fields.stream().filter(f -> f.name.equals(field.name) && f.desc.equals(STRING)).collect(java.util.stream.Collectors.toList()), "identity field");
		boolean samePackage = field.owner.lastIndexOf('/') == caller.lastIndexOf('/')
			&& field.owner.substring(0, field.owner.lastIndexOf('/') + 1).equals(caller.substring(0, caller.lastIndexOf('/') + 1));
		require((target.access & Opcodes.ACC_STATIC) != 0 && (target.access & Opcodes.ACC_PRIVATE) == 0
			&& ((target.access & Opcodes.ACC_PUBLIC) != 0 || samePackage), "Identity field is inaccessible from platform class");
	}

	private static boolean isStatic(MethodNode method)
	{
		return (method.access & Opcodes.ACC_STATIC) != 0;
	}

	private static boolean calls(MethodNode method, String owner, String name)
	{
		return calls(method, owner, name, null);
	}

	private static boolean calls(MethodNode method, String owner, String name, String descriptor)
	{
		for (AbstractInsnNode instruction : method.instructions)
		{
			if (instruction instanceof MethodInsnNode)
			{
				MethodInsnNode call = (MethodInsnNode) instruction;
				if (call.owner.equals(owner) && call.name.equals(name) && (descriptor == null || call.desc.equals(descriptor)))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean contains(MethodNode method, String text)
	{
		for (AbstractInsnNode instruction : method.instructions)
		{
			if (instruction instanceof LdcInsnNode && text.equals(((LdcInsnNode) instruction).cst))
			{
				return true;
			}
		}
		return false;
	}

	private static List<FieldInsnNode> stringFields(MethodNode method, int opcode, String owner)
	{
		Map<String, FieldInsnNode> fields = new LinkedHashMap<>();
		for (AbstractInsnNode instruction : method.instructions)
		{
			if (instruction instanceof FieldInsnNode && instruction.getOpcode() == opcode)
			{
				FieldInsnNode field = (FieldInsnNode) instruction;
				if (field.desc.equals(STRING) && (owner == null || field.owner.equals(owner)))
				{
					fields.put(field.owner + "." + field.name, field);
				}
			}
		}
		return new ArrayList<>(fields.values());
	}

	private static FieldInsnNode uniqueField(List<FieldInsnNode> fields, String label)
	{
		Map<String, FieldInsnNode> unique = new LinkedHashMap<>();
		fields.forEach(field -> unique.put(field.owner + "." + field.name, field));
		return only(new ArrayList<>(unique.values()), label);
	}

	private static <T> T only(List<T> candidates, String label)
	{
		require(candidates.size() == 1, "Expected one " + label + "; found " + candidates.size());
		return candidates.get(0);
	}

	private static void require(boolean condition, String message)
	{
		if (!condition)
		{
			throw new IllegalStateException(message);
		}
	}

	private static void replace(MethodNode method)
	{
		method.instructions.clear();
		method.tryCatchBlocks.clear();
		if (method.localVariables != null)
		{
			method.localVariables.clear();
		}
		method.visibleLocalVariableAnnotations = null;
		method.invisibleLocalVariableAnnotations = null;
		method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
		method.maxStack = 2;
	}

	/** Offline gate: resolve, transform and ask the JVM to link the actual classes. */
	public static void main(String[] args) throws Exception
	{
		if (args.length != 2 && args.length != 3)
		{
			throw new IllegalArgumentException("Usage: GamepackIdentityPatch <injected-client.jar> <capture.json> [account-hooks.properties]");
		}
		List<String> holders = new ArrayList<>();
		if (args.length == 3)
		{
			Properties accounts = new Properties();
			try (InputStream input = Files.newInputStream(Path.of(args[2])))
			{
				accounts.load(input);
			}
			for (String value : accounts.stringPropertyNames().stream().sorted().map(accounts::getProperty).toArray(String[]::new))
			{
				if (value.startsWith("holder:"))
				{
					holders.add(value.substring("holder:".length()));
				}
			}
		}
		try (JarFile jar = new JarFile(args[0]); InputStream capture = Files.newInputStream(Path.of(args[1])))
		{
			Map<String, byte[]> patched = prepare(jar, true, capture, holders);
			ClassLoader loader = new ClassLoader(GamepackIdentityPatch.class.getClassLoader())
			{
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException
				{
					try
					{
						byte[] bytes = patched.get(name);
						if (bytes == null)
						{
							JarEntry entry = jar.getJarEntry(name.replace('.', '/') + ".class");
							if (entry == null)
							{
								throw new ClassNotFoundException(name);
							}
							try (InputStream input = jar.getInputStream(entry))
							{
								bytes = input.readAllBytes();
							}
						}
						return defineClass(name, bytes, 0, bytes.length);
					}
					catch (IOException ex)
					{
						throw new ClassNotFoundException(name, ex);
					}
				}
			};
			for (String name : patched.keySet())
			{
				loader.loadClass(name).getDeclaredMethods();
			}
			for (String holder : holders)
			{
				int split = holder.lastIndexOf('.');
				Class<?> owner = loader.loadClass(holder.substring(0, split));
				java.lang.reflect.Method getter = owner.getDeclaredMethod(HolderAccessors.getterName(holder.substring(split + 1)));
				java.lang.reflect.Method setter = owner.getDeclaredMethod(holder.substring(split + 1), String.class);
				require(java.lang.reflect.Modifier.isStatic(getter.getModifiers()) && getter.getReturnType() == String.class
					&& java.lang.reflect.Modifier.isStatic(setter.getModifiers()), "Holder accessors did not link: " + holder);
			}
			System.out.println("Identity overrides linked: " + patched.size() + " classes; 4 method replacements; "
				+ holders.size() + " credential holder getters; game not started");
		}
	}
}
