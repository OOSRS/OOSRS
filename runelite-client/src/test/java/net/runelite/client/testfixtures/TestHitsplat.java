package net.runelite.client.testfixtures;

import net.runelite.api.Hitsplat;

/** Immutable fixture that retains the API's real isMine/isOthers rules. */
public final class TestHitsplat implements Hitsplat
{
    private final int type;
    private final int amount;
    private final int disappears;

    public TestHitsplat(int type, int amount, int disappears)
    {
        this.type = type;
        this.amount = amount;
        this.disappears = disappears;
    }

    @Override public int getHitsplatType() { return type; }
    @Override public int getAmount() { return amount; }
    @Override public int getDisappearsOnGameCycle() { return disappears; }
}
