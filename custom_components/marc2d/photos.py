"""The photos of the panel's screensaver: found in a media source and signed for the panel."""

from __future__ import annotations

from collections import deque
import logging

from homeassistant.components.media_player import (
    BrowseError,
    BrowseMedia,
    MediaClass,
    async_process_play_media_url,
)
from homeassistant.components.media_source import (
    Unresolvable,
    async_browse_media,
    async_resolve_media,
)
from homeassistant.core import HomeAssistant

_LOGGER = logging.getLogger(__name__)

# ponytail: a whole library is walked again on each request, every 10 minutes per panel. Past
# these limits it stops: enough for a screensaver, and the panel's memory and HA's time stay
# bounded. Caching the walk per folder would lift them.
MAX_PHOTOS = 2000
MAX_FOLDERS = 200


class PhotosUnavailable(Exception):
    """The folder cannot be read: the message says what to check."""


def is_photo(item: BrowseMedia) -> bool:
    """An image the panel can show. Sources set the class, the type or both."""
    return item.media_class == MediaClass.IMAGE or item.media_content_type.startswith("image/")


async def find_photos(hass: HomeAssistant, folder: str, subfolders: bool) -> list[BrowseMedia]:
    """The photos in the folder, and its subfolders if asked, by name: the order is the panel's."""
    photos: list[BrowseMedia] = []
    pending = deque([folder])
    walked = 0
    while pending and walked < MAX_FOLDERS and len(photos) < MAX_PHOTOS:
        current = pending.popleft()
        walked += 1
        try:
            listing = await async_browse_media(hass, current)
        except BrowseError as err:
            if current == folder:
                raise PhotosUnavailable(
                    f"Cannot open the photos folder {folder}: {err}. Check it in the panel's "
                    "settings, under Photos: it must be a folder of My media or a media-source:// "
                    "identifier from the media browser."
                ) from err
            _LOGGER.warning("Skipping the photos subfolder %s: %s", current, err)
            continue
        for child in listing.children or []:
            if is_photo(child):
                photos.append(child)
            elif child.can_expand and subfolders:
                pending.append(child.media_content_id)
    if pending or len(photos) > MAX_PHOTOS:
        _LOGGER.warning(
            "The photos folder %s has more than %s photos or %s folders: the rest are left out",
            folder,
            MAX_PHOTOS,
            MAX_FOLDERS,
        )
    photos = photos[:MAX_PHOTOS]
    photos.sort(key=lambda photo: photo.title.casefold())
    return photos


async def photo_urls(hass: HomeAssistant, folder: str, subfolders: bool) -> list[str]:
    """Where the panel downloads each photo.

    Signed for the connection that asks, so the panel never sends its token with them. Those of
    Home Assistant are paths, as the panel knows best the address it reaches it at. The signature
    lasts a day, and the panel asks again long before.
    """
    urls = []
    for photo in await find_photos(hass, folder, subfolders):
        try:
            media = await async_resolve_media(hass, photo.media_content_id, None)
        except Unresolvable as err:
            _LOGGER.warning("Skipping the photo %s: %s", photo.media_content_id, err)
            continue
        urls.append(async_process_play_media_url(hass, media.url, allow_relative_url=True))
    _LOGGER.debug("Found %s photos in %s", len(urls), folder)
    return urls
