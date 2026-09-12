package net.openosrs.api;

import net.openosrs.api.service.bank.BankService;
import net.openosrs.api.service.camera.CameraService;
import net.openosrs.api.service.combat.CombatService;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.dialogue.DialogueFlowRunner;
import net.openosrs.api.service.delay.TickDelayService;
import net.openosrs.api.service.emote.EmoteService;
import net.openosrs.api.service.equipment.EquipmentService;
import net.openosrs.api.service.ge.GrandExchangeService;
import net.openosrs.api.service.grounditem.GroundItemService;
import net.openosrs.api.service.house.HouseService;
import net.openosrs.api.service.inventory.InventoryService;
import net.openosrs.api.service.login.LoginService;
import net.openosrs.api.service.magic.MagicService;
import net.openosrs.api.service.makex.MakeXService;
import net.openosrs.api.service.map.MapUiService;
import net.openosrs.api.service.map.MapService;
import net.openosrs.api.service.movement.LocalPathfinder;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.movement.Walker;
import net.openosrs.api.service.movement.teleports.TeleportsService;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.player.PlayerService;
import net.openosrs.api.service.prayer.PrayerService;
import net.openosrs.api.service.quest.QuestService;
import net.openosrs.api.service.sailing.SailingService;
import net.openosrs.api.service.scene.SceneService;
import net.openosrs.api.service.shop.ShopService;
import net.openosrs.api.service.skill.SkillService;
import net.openosrs.api.service.tile.TileService;
import net.openosrs.api.service.tabs.TabsService;
import net.openosrs.api.service.trade.TradeService;
import net.openosrs.api.service.var.VarService;
import net.openosrs.api.service.widget.WidgetService;

/**
 * Static facade over the Interaction API services.
 * Services resolve lazily through {@link Context} so ordering is never an issue.
 */
public final class OpenOSRS
{
	private OpenOSRS()
	{
	}

	/** Resolve the lifetime registered for a currently running plugin instance. */
	public static net.openosrs.api.operation.OperationOwner owner(Object plugin)
	{
		return Context.getService(net.openosrs.api.operation.OperationOwners.class).get(plugin);
	}
	public static net.openosrs.api.concurrent.ClientActions actions()
	{
		return Context.getService(net.openosrs.api.concurrent.ClientActions.class);
	}

	public static NpcService npcs()
	{
		return Context.getService(NpcService.class);
	}

	public static ObjectService objects()
	{
		return Context.getService(ObjectService.class);
	}

	public static PlayerService players()
	{
		return Context.getService(PlayerService.class);
	}

	public static GroundItemService groundItems()
	{
		return Context.getService(GroundItemService.class);
	}

	public static TileService tiles()
	{
		return Context.getService(TileService.class);
	}

	public static InventoryService inventory()
	{
		return Context.getService(InventoryService.class);
	}

	public static WidgetService widgets()
	{
		return Context.getService(WidgetService.class);
	}

	public static EquipmentService equipment() { return Context.getService(EquipmentService.class); }
	public static DialogueService dialogue() { return Context.getService(DialogueService.class); }
	/** Creates a fresh handle. Retain it over ticks; prefer the owner overload. */
	public static DialogueFlowRunner dialogueFlow() { return dialogueFlow(new net.openosrs.api.operation.OperationOwner()); }
	public static DialogueFlowRunner dialogueFlow(net.openosrs.api.operation.OperationOwner owner)
	{
		return Context.getService(net.openosrs.api.service.dialogue.DialogueFlowFactory.class).newFlow(owner);
	}
	public static PrayerService prayers() { return Context.getService(PrayerService.class); }
	public static TabsService tabs() { return Context.getService(TabsService.class); }
	public static EmoteService emotes() { return Context.getService(EmoteService.class); }
	public static MagicService magic() { return Context.getService(MagicService.class); }
	public static MakeXService makeX() { return Context.getService(MakeXService.class); }
	public static BankService bank() { return Context.getService(BankService.class); }
	public static ShopService shop() { return Context.getService(ShopService.class); }
	public static TradeService trade() { return Context.getService(TradeService.class); }
	public static GrandExchangeService grandExchange() { return Context.getService(GrandExchangeService.class); }
	public static MapUiService mapUi() { return Context.getService(MapUiService.class); }
	public static SkillService skills() { return Context.getService(SkillService.class); }
	public static CombatService combat() { return Context.getService(CombatService.class); }
	public static VarService vars() { return Context.getService(VarService.class); }
	public static QuestService quests() { return Context.getService(QuestService.class); }
	public static SceneService scene() { return Context.getService(SceneService.class); }
	public static HouseService house() { return Context.getService(HouseService.class); }
	public static CameraService camera() { return Context.getService(CameraService.class); }
	public static MapService map() { return Context.getService(MapService.class); }
	public static SailingService sailing() { return Context.getService(SailingService.class); }
	public static LoginService login() { return Context.getService(LoginService.class); }

	public static MovementService movement() { return Context.getService(MovementService.class); }
	public static LocalPathfinder pathfinder() { return Context.getService(LocalPathfinder.class); }
	public static Walker walker() { return Context.getService(Walker.class); }
	public static TeleportsService teleports() { return Context.getService(TeleportsService.class); }
	public static TickDelayService delays() { return Context.getService(TickDelayService.class); }
}
