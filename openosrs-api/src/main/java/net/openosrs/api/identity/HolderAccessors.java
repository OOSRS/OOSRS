package net.openosrs.api.identity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Read access to game values kept in constant-dynamic {@code String[]} holders.
 *
 * <p>Some builds store a credential in a one-element array produced by a
 * {@code ConstantBootstraps.invoke} bootstrap and written by a static setter of
 * the exact shape {@code ldc condy; iconst_0; aload_0; aastore; return}. The array
 * exists only in the owner class's resolved constant pool: nothing outside that
 * class can load it, and there is no native getter to call.
 *
 * <p>This emits one public static getter next to the setter, loading the same
 * constant, so it returns the very array the game reads and writes. The getter
 * is marked synthetic and named after its setter.
 */
public final class HolderAccessors
{
	public static final String GETTER_SUFFIX = "$get";
	private static final String STRING_SETTER = "(Ljava/lang/String;)V";
	private static final String STRING_GETTER = "()Ljava/lang/String;";
	private static final int CONSTANT_DYNAMIC = 17;

	private HolderAccessors()
	{
	}

	public static String getterName(String setterName)
	{
		return setterName + GETTER_SUFFIX;
	}

	/**
	 * Add the getter for {@code owner.setterName}, or return the existing one.
	 *
	 * @throws IllegalStateException if the setter does not have the exact holder shape
	 */
	public static MethodNode ensureGetter(ClassNode owner, String setterName)
	{
		MethodNode setter = null;
		for (MethodNode method : owner.methods)
		{
			if (method.name.equals(setterName) && method.desc.equals(STRING_SETTER))
			{
				setter = method;
			}
		}
		ConstantDynamic holder = setter == null ? null : holder(setter);
		if (holder == null)
		{
			throw new IllegalStateException("Not a constant-dynamic holder setter: " + owner.name + "." + setterName);
		}

		String name = getterName(setterName);
		for (MethodNode method : owner.methods)
		{
			if (method.name.equals(name))
			{
				if (!method.desc.equals(STRING_GETTER))
				{
					throw new IllegalStateException("Holder getter name is taken: " + owner.name + "." + name);
				}
				return method;
			}
		}

		MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			name, STRING_GETTER, null, null);
		getter.instructions.add(new LdcInsnNode(holder));
		getter.instructions.add(new InsnNode(Opcodes.ICONST_0));
		getter.instructions.add(new InsnNode(Opcodes.AALOAD));
		getter.instructions.add(new InsnNode(Opcodes.ARETURN));
		getter.maxStack = 2;
		getter.maxLocals = 0;
		owner.methods.add(getter);
		return getter;
	}

	/**
	 * The constant written by a holder setter, or {@code null} if the method is not
	 * exactly {@code ldc condy String[]; iconst_0; aload_0; aastore; return}.
	 */
	public static ConstantDynamic holder(MethodNode method)
	{
		if ((method.access & Opcodes.ACC_STATIC) == 0 || !STRING_SETTER.equals(method.desc))
		{
			return null;
		}
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions)
		{
			if (instruction.getOpcode() >= 0)
			{
				code.add(instruction);
			}
		}
		if (code.size() != 5 || !(code.get(0) instanceof LdcInsnNode)
			|| !(((LdcInsnNode) code.get(0)).cst instanceof ConstantDynamic))
		{
			return null;
		}
		ConstantDynamic value = (ConstantDynamic) ((LdcInsnNode) code.get(0)).cst;
		if (!"[Ljava/lang/String;".equals(value.getDescriptor())
			|| code.get(1).getOpcode() != Opcodes.ICONST_0
			|| !(code.get(2) instanceof VarInsnNode) || code.get(2).getOpcode() != Opcodes.ALOAD
			|| ((VarInsnNode) code.get(2)).var != 0
			|| code.get(3).getOpcode() != Opcodes.AASTORE
			|| code.get(4).getOpcode() != Opcodes.RETURN)
		{
			return null;
		}
		return value;
	}

	/**
	 * Refuse to rewrite a class whose constant pool holds two value-equal mutable
	 * (array) dynamic constants.
	 *
	 * <p>ASM rebuilds the pool from the tree and merges equal constants. Two
	 * distinct holders that happened to share a bootstrap would then collapse into
	 * one array, silently aliasing credentials. Every current holder has its own
	 * factory method, so this never fires today; it exists so that a future build
	 * which changes that fails loudly instead of logging someone in wrong.
	 */
	public static void requireDistinctDynamics(byte[] original, String className)
	{
		ClassReader reader = new ClassReader(original);
		char[] buffer = new char[reader.getMaxStringLength()];
		Set<Object> seen = new HashSet<>();
		for (int item = 1; item < reader.getItemCount(); item++)
		{
			int offset = reader.getItem(item);
			if (offset <= 0 || reader.readByte(offset - 1) != CONSTANT_DYNAMIC)
			{
				continue;
			}
			Object constant = reader.readConst(item, buffer);
			// Only mutable (array) constants carry identity. Equal immutable constants
			// merge harmlessly and do occur: the call-stack checker is resolved from
			// two value-equal String constants in current builds.
			if (constant instanceof ConstantDynamic && ((ConstantDynamic) constant).getDescriptor().startsWith("[")
				&& !seen.add(constant))
			{
				throw new IllegalStateException("Class " + className
					+ " has value-equal mutable dynamic constants; rewriting it would merge them");
			}
		}
	}
}
