package net.runelite.client.plugins;

import com.openosrs.client.config.OpenOSRSConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class RepositoryDeduplicationTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> values()
    {
        return Arrays.asList(new Object[][]{{"", ""}, {";", ";"}, {";;", ";;"}, {"a", "a"},
            {"a;a", "a"}, {"a;b;a", "a;b"}, {"b;a;b", "b;a"}});
    }
    private final String input, expected;
    public RepositoryDeduplicationTest(String input, String expected) { this.input = input; this.expected = expected; }
    @Test public void duplicateRemovalPreservesFirstSeenOrderAndEmptyConfigurations() throws Exception
    {
        OPRSExternalPluginManager manager = new OPRSExternalPluginManager();
        OpenOSRSConfig config = mock(OpenOSRSConfig.class);
        when(config.getExternalRepositories()).thenReturn(input);
        Field field = OPRSExternalPluginManager.class.getDeclaredField("openOSRSConfig");
        field.setAccessible(true); field.set(manager, config);
        Method deduplicate = OPRSExternalPluginManager.class.getDeclaredMethod("duplicateCheck");
        deduplicate.setAccessible(true); deduplicate.invoke(manager);
        if (input.equals(expected)) verify(config, never()).setExternalRepositories(anyString());
        else verify(config).setExternalRepositories(expected);
    }
}
