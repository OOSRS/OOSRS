package net.openosrs.api.input;

import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;

/**
 * Chooses which backend delivers an interaction.
 *
 * <p>Precedence is deliberate and narrow: a thread-pinned {@link InputScope}
 * always wins, otherwise the configured default applies. A cursor backend is
 * only consulted when one has been registered and reports itself available, so
 * an install with no cursor support behaves exactly as it did before.
 *
	 * <p>Fallback is opt-in. When a cursor request
 * cannot be served and fallback is disabled, the rejection reaches the caller
 * instead of quietly becoming a different kind of interaction.
 */
@Slf4j
@Singleton
public class InputRouter
{
	private final PacketInputBackend packets;
	private final InputSettings settings;
	@Inject private net.openosrs.api.operation.OperationLeases leases;

	private volatile InputBackend cursor;
	private volatile MouseDriver mouseDriver;

	@Inject
	public InputRouter(PacketInputBackend packets, InputSettings settings)
	{
		this.packets = packets;
		this.settings = settings;
	}

	/**
	 * Install the cursor backend. Kept as registration rather than injection so
	 * that openosrs-api does not depend on the canvas layer that implements it.
	 */
	public void setCursorBackend(InputBackend backend)
	{
		if (backend != null && backend.mode() != InputMode.HUMAN_MOUSE)
		{
			throw new IllegalArgumentException("cursor backend must report HUMAN_MOUSE");
		}
		this.cursor = backend;
		if (backend instanceof MouseDriver)
		{
			this.mouseDriver = (MouseDriver) backend;
		}
		else
		{
			this.mouseDriver = null;
		}
	}

	public Optional<InputBackend> cursorBackend()
	{
		return Optional.ofNullable(cursor);
	}

	public void setMouseDriver(MouseDriver driver)
	{
		this.mouseDriver = driver;
	}

	public MouseDriver getMouseDriver()
	{
		return mouseDriver;
	}

	public boolean isCursorAvailable()
	{
		InputBackend backend = cursor;
		return backend != null && backend.isAvailable();
	}

	/** Requested mode before target support or fallback is considered. */
	public InputMode selectedMode()
	{
		InputMode pinned = InputScope.current();
		return pinned != null ? pinned : settings.getDefaultMode();
	}

	/** The mode that would be used right now for a request of this shape. */
	public InputMode resolveMode(MenuRequest request)
	{
		InputMode pinned = InputScope.current();
		InputMode wanted = pinned != null ? pinned : settings.getDefaultMode();
		if (wanted != InputMode.HUMAN_MOUSE)
		{
			return InputMode.PACKET;
		}
		InputBackend backend = cursor;
		if (backend == null || !backend.isAvailable() || !backend.supports(request))
		{
			return allowFallback(pinned, request) ? InputMode.PACKET : InputMode.HUMAN_MOUSE;
		}
		return InputMode.HUMAN_MOUSE;
	}

	public SubmissionResult submit(MenuRequest request)
	{
		if (leases != null) leases.requireAccess(net.openosrs.api.operation.OperationLeases.Resource.SELECTION);
		if (request == null)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_INVALID_INPUT, "A request is required");
		}

		InputMode pinned = InputScope.current();
		InputMode wanted = pinned != null ? pinned : settings.getDefaultMode();

		if (wanted == InputMode.HUMAN_MOUSE)
		{
			InputBackend backend = cursor;
			if (backend == null || !backend.isAvailable())
			{
				if (!allowFallback(pinned, request))
				{
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION,
						"Cursor input is selected but no cursor backend is available");
				}
			}
			else if (backend.isBusy())
			{
				return backend.submit(request);
			}
			else if (!backend.supports(request))
			{
				if (!allowFallback(pinned, request))
				{
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION,
						"Cursor input cannot deliver this request");
				}
				log.debug("Cursor backend declined {}, using the native pipeline", request.getAction());
			}
			else
			{
				return backend.submit(request);
			}
		}

		// The native bridge has no room for a click point; drop it on the way through.
		return packets.submit(request.withoutPoint());
	}

	/**
	 * A thread that explicitly pinned the cursor asked for the cursor. Honour
	 * that over the global convenience setting, otherwise a pinned scope would
	 * be indistinguishable from no scope at all.
	 */
	private boolean allowFallback(InputMode pinned, MenuRequest request)
	{
		if (pinned == InputMode.HUMAN_MOUSE || (request != null && request.getAction() == net.runelite.api.MenuAction.WALK))
		{
			return false;
		}
		return settings.isFallbackToPackets();
	}

	public boolean prepare(HoverIntent intent)
	{
		InputMode pinned = InputScope.current();
		InputMode wanted = pinned != null ? pinned : settings.getDefaultMode();
		InputBackend backend = cursor;
		return wanted == InputMode.HUMAN_MOUSE && backend != null && backend.isAvailable()
			&& backend.prepare(intent);
	}

	public void cancelPreparation()
	{
		InputBackend backend = cursor;
		if (backend != null) backend.cancelPreparation();
	}

	public void anticipate(MenuRequest request)
	{
		InputBackend backend = cursor;
		if (backend == null || request == null || !backend.isAvailable())
		{
			return;
		}
		if (resolveMode(request) == InputMode.HUMAN_MOUSE)
		{
			backend.anticipate(request);
		}
	}
}
