"""What all the integration's tests share."""

import pytest

from custom_components.marc2d.update import LATEST_RELEASE


@pytest.fixture(autouse=True)
def no_release_published(aioclient_mock) -> None:
    """Tests have no Internet: the panel's update looks for a release as soon as it exists."""
    aioclient_mock.get(LATEST_RELEASE, status=404)
