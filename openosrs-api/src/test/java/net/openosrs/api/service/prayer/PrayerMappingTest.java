package net.openosrs.api.service.prayer;

import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrayerMappingTest
{
	private final Client client = mock(Client.class);
	private final WidgetService widgets = mock(WidgetService.class);
	private final PrayerService prayers = new PrayerService(client, widgets);

	PrayerMappingTest()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getRevision()).thenReturn(240);
		when(client.getVarps()).thenReturn(new int[20000]);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(99);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(99);
		when(client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED)).thenReturn(1);
	}

	private WidgetRef widget(int component, String name)
	{
		WidgetRef widget = mock(WidgetRef.class);
		when(widget.getId()).thenReturn(component);
		when(widget.isVisible()).thenReturn(true);
		when(widget.getName()).thenReturn("<col=ff9040>" + name + "</col>");
		when(widget.hasAction("Activate")).thenReturn(true);
		when(widgets.get(component)).thenReturn(widget);
		return widget;
	}

	@Test void auguryUsesItsOwnComponent()
	{
		WidgetRef augury = widget(InterfaceID.Prayerbook.PRAYER28, "Augury");
		widget(InterfaceID.Prayerbook.PRAYER1, "Thick Skin");
		prayers.toggle(Prayer.AUGURY, true);
		verify(widgets).interact(augury, "Activate");
	}

	@Test void wrongBookIsRejected()
	{
		widget(InterfaceID.Prayerbook.PRAYER1, "Thick Skin");
		when(client.getVarbitValue(VarbitID.PRAYERBOOK)).thenReturn(1);
		assertThrows(IllegalStateException.class, () -> prayers.toggle(Prayer.THICK_SKIN, true));
		verify(widgets, never()).click(any());
	}

	@Test void allOriginalStandardControlsFollowReferenceOrder()
	{
		Prayer[] order = { Prayer.THICK_SKIN, Prayer.BURST_OF_STRENGTH, Prayer.CLARITY_OF_THOUGHT,
			Prayer.ROCK_SKIN, Prayer.SUPERHUMAN_STRENGTH, Prayer.IMPROVED_REFLEXES,
			Prayer.RAPID_RESTORE, Prayer.RAPID_HEAL, Prayer.PROTECT_ITEM, Prayer.STEEL_SKIN,
			Prayer.ULTIMATE_STRENGTH, Prayer.INCREDIBLE_REFLEXES, Prayer.PROTECT_FROM_MAGIC,
			Prayer.PROTECT_FROM_MISSILES, Prayer.PROTECT_FROM_MELEE, Prayer.RETRIBUTION,
			Prayer.REDEMPTION, Prayer.SMITE, Prayer.SHARP_EYE, Prayer.HAWK_EYE, Prayer.EAGLE_EYE,
			Prayer.MYSTIC_WILL, Prayer.MYSTIC_LORE, Prayer.MYSTIC_MIGHT, Prayer.RIGOUR,
			Prayer.CHIVALRY, Prayer.PIETY, Prayer.AUGURY, Prayer.PRESERVE };
		when(client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.PRAYER_PRESERVE_UNLOCKED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE)).thenReturn(8);
		for (int i = 0; i < order.length; i++)
		{
			WidgetRef control = widget(InterfaceID.Prayerbook.PRAYER1 + i, order[i].name().replace('_', ' '));
			prayers.ensureActive(order[i]);
			verify(widgets).interact(control, "Activate");
		}
	}

	@Test void replacementPrayersRequireExactLiveNamesAndUnlocks()
	{
		when(client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED)).thenReturn(1);
		WidgetRef deadeye = widget(InterfaceID.Prayerbook.PRAYER21, "Deadeye");
		WidgetRef vigour = widget(InterfaceID.Prayerbook.PRAYER24, "Mystic Vigour");
		prayers.ensureActive(Prayer.DEADEYE);
		prayers.ensureActive(Prayer.MYSTIC_VIGOUR);
		verify(widgets).interact(deadeye, "Activate");
		verify(widgets).interact(vigour, "Activate");
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.EAGLE_EYE));
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.MYSTIC_MIGHT));
	}

	@Test void hiddenLockedAndUnexpectedControlsDoNotDispatch()
	{
		WidgetRef augury = widget(InterfaceID.Prayerbook.PRAYER28, "Augury");
		when(augury.isVisible()).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.AUGURY));
		when(augury.isVisible()).thenReturn(true);
		when(client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED)).thenReturn(0);
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.AUGURY));
		when(client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED)).thenReturn(1);
		when(augury.getName()).thenReturn("Thick Skin");
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.AUGURY));
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.RP_REJUVENATION));
		verify(widgets, never()).interact(any(), anyString());
	}

	@Test void observedStatePreventsRetoggleAndRejectionPropagates()
	{
		WidgetRef augury = widget(InterfaceID.Prayerbook.PRAYER28, "Augury");
		when(client.isPrayerActive(Prayer.AUGURY)).thenReturn(true);
		prayers.ensureActive(Prayer.AUGURY);
		verifyNoInteractions(widgets);
		when(client.isPrayerActive(Prayer.AUGURY)).thenReturn(false);
		doThrow(new IllegalStateException("rejected")).when(widgets).interact(augury, "Activate");
		assertThrows(IllegalStateException.class, () -> prayers.ensureActive(Prayer.AUGURY));
		assertFalse(prayers.isActive(Prayer.AUGURY));
	}

	@Test void wrongThreadAndRevisionDoNotReadWidgets()
	{
		when(client.isClientThread()).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> prayers.toggle(Prayer.THICK_SKIN));
		when(client.isClientThread()).thenReturn(true);
		when(client.getRevision()).thenReturn(241);
		assertThrows(IllegalStateException.class, () -> prayers.toggle(Prayer.THICK_SKIN));
		verifyNoInteractions(widgets);
	}
}
