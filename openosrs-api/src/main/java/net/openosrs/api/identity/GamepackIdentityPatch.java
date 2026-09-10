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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
		if (!Boolean.parseBoolean(System.getProperty("oos.identity.enabled", "true")))
		{
			log.info("Gamepack identity overrides disabled");
			return Map.of();
		}
		try (InputStream capture = GamepackIdentityPatch.class.getResourceAsStream("/identity-callstack.json"))
		{
			return prepare(jar, capture);
		}
	}

	private static Map<String, byte[]> prepare(JarFile jar, InputStream capture) throws IOException
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

		Map<String, ClassNode> classes = new LinkedHashMap<>();
		for (JarEntry entry : java.util.Collections.list(jar.entries()))
		{
			if (entry.getName().endsWith(".class"))
			{
				try (InputStream input = jar.getInputStream(entry))
				{
					ClassNode node = new ClassNode();
					new ClassReader(input).accept(node, 0);
					classes.put(node.name, node);
				}
			}
		}
		ClassNode client = classes.get("client");
		require(client != null, "Game client class is missing");
		List<MethodNode> checks = new ArrayList<>();
		List<MethodNode> combiners = new ArrayList<>();
		List<FieldInsnNode> accountFields = new ArrayList<>();
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
					// Character ID's property read is immediately followed by its
					// String field assignment, independent of obfuscated names.
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
								accountFields.add(field);
							}
							break;
						}
					}
				}
			}
		}
		MethodNode check = only(checks, "call-stack checker");
		MethodNode combiner = only(combiners, "packed-stack combiner");
		FieldInsnNode characterId = uniqueField(accountFields, "character ID");
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
		validateFieldAccess(classes, characterId, platform.name);
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
		uuid.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, characterId.owner, characterId.name, STRING));
		uuid.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, username.owner, username.name, STRING));
		uuid.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolve", "(" + STRING + STRING + ")" + STRING, false));
		uuid.instructions.add(new InsnNode(Opcodes.ARETURN));

		Map<String, byte[]> patched = new LinkedHashMap<>();
		for (ClassNode node : List.of(client, platform))
		{
			// Existing method frames stay untouched; replacements are straight
			// line methods and need only their maximum stack size recomputed.
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			patched.put(node.name.replace('/', '.'), writer.toByteArray());
		}
		log.info("Identity overrides ready: RuneLite {}, rev {}, client={}, stack={}, UUID={}.{}{}",
			reference.get("runeliteVersion").getAsString(), reference.get("revision").getAsInt(), jarHash,
			reference.get("stackSha256").getAsString(), platform.name, uuid.name, uuid.desc);
		return patched;
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
		if (args.length != 2)
		{
			throw new IllegalArgumentException("Usage: GamepackIdentityPatch <injected-client.jar> <capture.json>");
		}
		try (JarFile jar = new JarFile(args[0]); InputStream capture = Files.newInputStream(Path.of(args[1])))
		{
			Map<String, byte[]> patched = prepare(jar, capture);
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
			System.out.println("Identity overrides linked: " + patched.size() + " classes; 4 method replacements; game not started");
		}
	}
}
