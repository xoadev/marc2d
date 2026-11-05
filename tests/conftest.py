"""What all the integration's tests share."""

import pytest


@pytest.fixture(autouse=True)
def no_release_published(aioclient_mock) -> None:
    """Tests have no Internet: the panel's update looks for a release as soon as it exists."""
    aioclient_mock.get("https://api.github.com/repos/xoadev/marc2d/releases/latest", status=404)
