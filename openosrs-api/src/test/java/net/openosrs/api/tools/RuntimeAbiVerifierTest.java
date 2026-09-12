package net.openosrs.api.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAbiVerifierTest
{
    @SuppressWarnings("unchecked")
    private boolean resolves(String descriptor, ClassNode... nodes) throws Exception
    {
        RuntimeAbiVerifier verifier = new RuntimeAbiVerifier();
        Field field = RuntimeAbiVerifier.class.getDeclaredField("classes");
        field.setAccessible(true);
        Map<String, ClassNode> classes = (Map<String, ClassNode>) field.get(verifier);
        for (ClassNode node : nodes) classes.put(node.name,node);
        Method method = RuntimeAbiVerifier.class.getDeclaredMethod("concrete",String.class,String.class,String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(verifier,"Concrete","value",descriptor);
    }
    private ClassNode type(String name, String parent, String... interfaces)
    {
        ClassNode node = new ClassNode();
        node.name=name; node.superName=parent;
        java.util.Collections.addAll(node.interfaces,interfaces);
        return node;
    }
    private void method(ClassNode node, String descriptor, int extra)
    {
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC|extra,"value",descriptor,null,null));
    }
    @Test void returnTypeIsPartOfTheObligation() throws Exception
    {
        ClassNode concrete=type("Concrete","java/lang/Object");
        method(concrete,"()Ljava/lang/Object;",0);
        assertFalse(resolves("()Ljava/lang/String;",concrete));
        method(concrete,"()Ljava/lang/String;",Opcodes.ACC_BRIDGE|Opcodes.ACC_SYNTHETIC);
        assertTrue(resolves("()Ljava/lang/String;",concrete));
    }
    @Test void abstractSuperclassDoesNotImplementContract() throws Exception
    {
        ClassNode parent=type("Parent","java/lang/Object"); method(parent,"()I",Opcodes.ACC_ABSTRACT);
        ClassNode concrete=type("Concrete","Parent");
        assertFalse(resolves("()I",parent,concrete));
    }
    @Test void inheritedDefaultWorksButAbstractOverrideDoesNot() throws Exception
    {
        ClassNode api=type("Api",null); method(api,"()I",0);
        ClassNode concrete=type("Concrete","java/lang/Object","Api");
        assertTrue(resolves("()I",api,concrete));
        method(concrete,"()I",Opcodes.ACC_ABSTRACT);
        assertFalse(resolves("()I",api,concrete));
    }
    @Test void staticMethodCannotImplementInstanceContract() throws Exception
    {
        ClassNode concrete=type("Concrete","java/lang/Object"); method(concrete,"()I",Opcodes.ACC_STATIC);
        assertFalse(resolves("()I",concrete));
    }
    @Test void conflictingDefaultsCannotBeChosenArbitrarily() throws Exception
    {
        ClassNode a=type("A",null); method(a,"()I",0);
        ClassNode b=type("B",null); method(b,"()I",0);
        ClassNode concrete=type("Concrete","java/lang/Object","A","B");
        assertFalse(resolves("()I",a,b,concrete));
    }
}
