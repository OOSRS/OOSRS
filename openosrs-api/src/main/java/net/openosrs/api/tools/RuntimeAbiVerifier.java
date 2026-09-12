package net.openosrs.api.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Bytecode-only gate: no game class loading, constructors, packet sends or network. */
public final class RuntimeAbiVerifier
{
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Set<String> game = new TreeSet<>();
    private final Set<String> application = new TreeSet<>();
    private final List<String> errors = new ArrayList<>();
    private final Set<String> missingClasses = new TreeSet<>();
    private int obligations;
    private int references;

    private void read(InputStream input, Set<String> names) throws Exception
    {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        classes.put(node.name, node);
        names.add(node.name);
    }

    private void load(Path path, Set<String> names) throws Exception
    {
        if (Files.isDirectory(path))
        {
            try (var files = Files.walk(path))
            {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator)
                {
                    try (InputStream in = Files.newInputStream(file)) { read(in, names); }
                }
            }
        }
        else if (Files.isRegularFile(path))
        {
            try (JarFile jar = new JarFile(path.toFile()))
            {
                for (var entries = jar.entries(); entries.hasMoreElements();)
                {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    try (InputStream in = jar.getInputStream(entry)) { read(in, names); }
                }
            }
        }
        else { throw new IllegalArgumentException("Missing ABI input: " + path); }
    }

    private ClassNode node(String name)
    {
        if (name == null) return null;
        ClassNode node = classes.get(name);
        if (node != null) return node;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class"))
        {
            if (in != null)
            {
                node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
                classes.put(name, node);
                return node;
            }
        }
        catch (Exception exception) { errors.add("Cannot inspect " + name + ": " + exception.getClass().getSimpleName()); }
        missingClasses.add(name);
        return null;
    }

    private void interfaces(String name, Set<String> result, Set<String> seen)
    {
        if (name == null || !seen.add(name)) return;
        ClassNode node = node(name);
        if (node == null) return;
        for (String parent : node.interfaces)
        {
            result.add(parent);
            interfaces(parent, result, seen);
        }
        interfaces(node.superName, result, seen);
    }

    private MethodNode declared(String owner, String name, String descriptor)
    {
        ClassNode node = node(owner);
        if (node != null) for (MethodNode method : node.methods)
        {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private boolean concrete(String owner, String name, String descriptor)
    {
        // The most specific class declaration wins, including an abstract override.
        for (String current = owner; current != null;)
        {
            ClassNode node = node(current);
            if (node == null) return false;
            MethodNode method = declared(current, name, descriptor);
            if (method != null) return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0
                && (method.access & Opcodes.ACC_PUBLIC) != 0;
            current = node.superName;
        }
        Set<String> all = new TreeSet<>();
        interfaces(owner, all, new HashSet<>());
        List<String> declarations = new ArrayList<>();
        for (String candidate : all) if (declared(candidate, name, descriptor) != null) declarations.add(candidate);
        List<String> maximal = new ArrayList<>();
        for (String candidate : declarations)
        {
            boolean overridden = false;
            for (String other : declarations)
            {
                if (candidate.equals(other)) continue;
                Set<String> ancestors = new HashSet<>();
                interfaces(other, ancestors, new HashSet<>());
                if (ancestors.contains(candidate)) { overridden = true; break; }
            }
            if (!overridden) maximal.add(candidate);
        }
        long defaults = maximal.stream().map(i -> declared(i, name, descriptor))
            .filter(m -> (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0).count();
        return defaults == 1;
    }

    private MethodNode resolveMethod(String owner, String name, String descriptor, Set<String> seen)
    {
        if (owner == null || !seen.add(owner)) return null;
        ClassNode node = node(owner);
        if (node == null) return null;
        MethodNode method = declared(owner, name, descriptor);
        if (method != null || name.equals("<init>")) return method;
        method = resolveMethod(node.superName, name, descriptor, seen);
        if (method != null) return method;
        for (String parent : node.interfaces)
        {
            method = resolveMethod(parent, name, descriptor, seen);
            if (method != null) return method;
        }
        return null;
    }

    private FieldNode resolveField(String owner, String name, String descriptor, Set<String> seen)
    {
        if (owner == null || !seen.add(owner)) return null;
        ClassNode node = node(owner);
        if (node == null) return null;
        for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(descriptor)) return field;
        for (String parent : node.interfaces)
        {
            FieldNode field = resolveField(parent, name, descriptor, seen);
            if (field != null) return field;
        }
        return resolveField(node.superName, name, descriptor, seen);
    }

    private boolean relevant(String owner)
    {
        return owner.startsWith("net/runelite/") || owner.startsWith("net/openosrs/") || owner.startsWith("com/openosrs/");
    }

    private void scan()
    {
        for (String name : game)
        {
            ClassNode impl = node(name);
            if ((impl.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) continue;
            Set<String> all = new TreeSet<>();
            interfaces(name, all, new HashSet<>());
            Set<String> seen = new HashSet<>();
            for (String contract : all)
            {
                if (!contract.startsWith("net/runelite/api/")) continue;
                ClassNode api = node(contract);
                if (api == null) continue;
                for (MethodNode method : api.methods)
                {
                    if ((method.access & Opcodes.ACC_ABSTRACT) == 0 || !seen.add(method.name + method.desc)) continue;
                    obligations++;
                    if (!concrete(name, method.name, method.desc)) errors.add("Missing implementation: " + name + " -> " + contract + "." + method.name + method.desc);
                }
            }
        }
        Set<String> callers = new TreeSet<>(game);
        for (String name : application) if (relevant(name)) callers.add(name);
        for (String name : callers) for (MethodNode method : node(name).methods) for (AbstractInsnNode instruction : method.instructions)
        {
            if (instruction instanceof MethodInsnNode)
            {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!relevant(call.owner)) continue;
                references++;
                MethodNode target = resolveMethod(call.owner, call.name, call.desc, new HashSet<>());
                if (target == null || ((target.access & Opcodes.ACC_STATIC) != 0) != (call.getOpcode() == Opcodes.INVOKESTATIC))
                    errors.add("Unresolved method: " + name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
            }
            else if (instruction instanceof FieldInsnNode)
            {
                FieldInsnNode call = (FieldInsnNode) instruction;
                if (!relevant(call.owner)) continue;
                references++;
                FieldNode target = resolveField(call.owner, call.name, call.desc, new HashSet<>());
                boolean isStatic = call.getOpcode() == Opcodes.GETSTATIC || call.getOpcode() == Opcodes.PUTSTATIC;
                if (target == null || ((target.access & Opcodes.ACC_STATIC) != 0) != isStatic)
                    errors.add("Unresolved field: " + name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
            }
        }
        for (String missing : missingClasses) errors.add("Incomplete hierarchy: " + missing);
        if (obligations == 0 || references == 0 || game.isEmpty()) errors.add("Empty ABI scan");
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length < 4) throw new IllegalArgumentException("gamepack pins report classpath...");
        Path gamepack = Path.of(args[0]);
        Properties pins = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(args[1]))) { pins.load(in); }
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(gamepack))) hash.append(String.format("%02x", b & 255));
        if (!hash.toString().equals(pins.getProperty("sha256"))) throw new IllegalStateException("Gamepack identity mismatch");
        RuntimeAbiVerifier verifier = new RuntimeAbiVerifier();
        for (int i = 3; i < args.length; i++) verifier.load(Path.of(args[i]), verifier.application);
        verifier.load(gamepack, verifier.game);
        verifier.scan();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("gamepackSha256", hash.toString());
        report.put("revision", pins.getProperty("revision"));
        report.put("obligations", verifier.obligations);
        report.put("references", verifier.references);
        report.put("errors", verifier.errors);
        String baselinePath = System.getProperty("openosrs.abiBaseline");
        Set<String> unexpected = new TreeSet<>(verifier.errors);
        if (baselinePath != null)
        {
            JsonObject baseline = new GsonBuilder().create().fromJson(Files.readString(Path.of(baselinePath)), JsonObject.class);
            if (!hash.toString().equals(baseline.get("gamepackSha256").getAsString())
                || !pins.getProperty("revision").equals(baseline.get("revision").getAsString()))
                throw new IllegalStateException("Deferred ABI baseline belongs to another gamepack");
            Set<String> deferred = new TreeSet<>();
            baseline.getAsJsonArray("errors").forEach(error -> deferred.add(error.getAsString()));
            unexpected.removeAll(deferred);
            Set<String> stale = new TreeSet<>(deferred);
            stale.removeAll(verifier.errors);
            report.put("deferredErrors", deferred);
            report.put("unexpectedErrors", unexpected);
            report.put("staleDeferrals", stale);
            if (!stale.isEmpty()) unexpected.add("Resolved ABI deferrals must be removed from the baseline: " + stale);
            System.out.println("Release ABI: " + deferred.size() + " explicitly deferred obligations; strict compatibility is incomplete");
        }
        Path output = Path.of(args[2]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("ABI: " + verifier.obligations + " obligations, " + verifier.references + " references, " + verifier.errors.size() + " errors");
        if (!unexpected.isEmpty()) throw new IllegalStateException("ABI gate failed; see " + output);
    }
}
