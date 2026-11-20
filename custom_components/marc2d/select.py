"""The state that the droid shows."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .entity import PanelEntity
from .panel_hub import MOODS, get_hub


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the state."""
    add_entities([PanelMood(entry, get_hub(hass, entry.entry_id), "mood")])


class PanelMood(PanelEntity, SelectEntity, RestoreEntity):
    """Restored on restart, so that an active alarm is not forgotten."""

    _attr_name = "State"
    _attr_options = MOODS

    @property
    def current_option(self) -> str:
        """Return the state."""
        return self._hub.mood

    async def async_added_to_hass(self) -> None:
        """Recover the state and tell the panel."""
        await super().async_added_to_hass()
        last = await self.async_get_last_state()
        if last is not None and last.state in MOODS:
            await self.async_select_option(last.state)

    async def async_select_option(self, option: str) -> None:
        """Change the state."""
        self._hub.mood = option
        self._hub.send({"kind": "mood", "value": option})
        self.async_write_ha_state()
