package net.runelite.client.rs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientConfigContractTest
{
    @Test public void malformedParameterIsAnIoFailure() throws Exception
    {
        try { ClientConfigLoader.parse("param=broken\n".getBytes(StandardCharsets.UTF_8)); fail("malformed parameter accepted"); }
        catch (IOException expected) { }
    }
    @Test public void oversizedConfigIsRejected() throws Exception
    {
        try { ClientConfigLoader.parse(("param=1=" + "x".repeat(300000)).getBytes(StandardCharsets.UTF_8)); fail("oversized body accepted"); }
        catch (IOException expected) { }
    }
    @Test public void loaderCannotLaunchExternalConfigFetchProcesses() throws Exception
    {
        try (java.io.InputStream input = ClientLoader.class.getResourceAsStream("ClientLoader.class"))
        {
            String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse("external process pre-pass remains", constants.contains("java/lang/ProcessBuilder"));
            assertFalse("shared temporary config remains", constants.contains("/tmp/.oos_jav_config.ws"));
        }
    }
}
