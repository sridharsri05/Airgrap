"""Which address a discovered device is actually dialled on.

A phone on a dual-stack network advertises both an IPv4 and an IPv6 address,
and zeroconf returns them in no useful order. Taking the first one reached a
real user's screen as "Could not reach Motorola moto g85 5G" -- while the
device list, one card below, showed a perfectly reachable 192.168 address for
the same phone.
"""

from __future__ import annotations

from airgrab.discovery import prefer_ipv4


def test_ipv4_wins_even_when_ipv6_is_listed_first():
    assert prefer_ipv4(["2405:201:e019:7057:8e5:c7ff:fe6d:4c26", "192.168.29.246"]) == (
        "192.168.29.246"
    )


def test_ipv4_alone_is_returned():
    assert prefer_ipv4(["192.168.29.246"]) == "192.168.29.246"


def test_a_routable_ipv6_is_used_when_there_is_no_ipv4():
    # Not every network has IPv4. Refusing to connect at all would be worse
    # than trying the address the device actually gave us.
    assert prefer_ipv4(["2405:201:e019:7057:8e5:c7ff:fe6d:4c26"]) == (
        "2405:201:e019:7057:8e5:c7ff:fe6d:4c26"
    )


def test_link_local_ipv6_is_skipped():
    # fe80:: needs a scope identifier this code does not carry, so it can
    # never be dialled successfully -- offering it would only produce a
    # confusing failure later.
    assert prefer_ipv4(["fe80::8e5:c7ff:fe6d:4c26"]) is None


def test_link_local_is_skipped_in_favour_of_anything_usable():
    assert prefer_ipv4(["fe80::1", "192.168.1.5"]) == "192.168.1.5"


def test_nothing_advertised_means_nothing_to_dial():
    assert prefer_ipv4([]) is None
