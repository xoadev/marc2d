"""marc2d: a wall panel that registers itself in Home Assistant with a QR code."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .config_flow import CONF_USER_ID
from .enrollment import async_remove_user


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Nothing to start yet: the panel still gets its configuration from the script."""
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Nothing to stop."""
    return True


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Removing the panel revokes its access."""
    await async_remove_user(hass, entry.data[CONF_USER_ID])
