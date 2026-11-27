"""Diagnostic frames and explicitly bounded traffic for the isolated lab."""

import threading
import time

from scapy.all import Ether, Raw, conf, sendp
from attacks.common import log_event

_stop = threading.Event()
_thread = None
_failure = None


def laboratory_links(net, target):
    expected = {"s0": 10, "s1": 1, "s2": 2}
    if target != "s0" or {s.name for s in net.switches} != set(expected):
        raise ValueError("The probe requires the three-switch Group 8 laboratory")
    if any(int(s.dpid, 16) != expected[s.name] for s in net.switches):
        raise ValueError("Unexpected laboratory DPIDs")
    if len(net.hosts) != 10:
        raise ValueError("The probe requires ten legitimate hosts")
    host_counts = {"s1": 0, "s2": 0}
    for host in net.hosts:
        peers = []
        for intf in host.intfList():
            if intf.link is not None:
                link = intf.link
                peers.append((link.intf2 if link.intf1 == intf else link.intf1).node.name)
        if len(peers) != 1 or peers[0] not in host_counts:
            raise ValueError("Every host must have one link to s1 or s2")
        host_counts[peers[0]] += 1
    if host_counts != {"s1": 5, "s2": 5}:
        raise ValueError("Expected five hosts on each trusted switch")
    links = []
    for intf in net.get(target).intfList():
        if intf.link is None:
            continue
        link = intf.link
        peer = link.intf2 if link.intf1 == intf else link.intf1
        links.append((intf, peer))
    if len(links) != 2 or {peer.node.name for _, peer in links} != {"s1", "s2"}:
        raise ValueError("Expected one internal link from s0 to each trusted switch")
    return sorted(links, key=lambda pair: pair[1].node.name)


def _frame(intf, peer):
    return Ether(src=intf.MAC(), dst=peer.MAC(), type=0x88b5) / Raw(
        load=b"GROUP8-LAB-PROBE".ljust(46, b"\x00"))


def _send_bounded(links, rate, duration):
    global _failure
    sockets = []
    counts = [{"attempted": 0, "sent": 0, "errors": 0} for _ in links]
    started = time.monotonic()
    try:
        for intf, _ in links:
            sock = conf.L2socket(iface=intf.name)
            sockets.append(sock)
            sock.outs.settimeout(1.0)
        frames = [_frame(intf, peer) for intf, peer in links]
        due = time.monotonic()
        for index in range(min(rate * duration, 3000)):
            if _stop.wait(max(0.0, due - time.monotonic())):
                break
            if time.monotonic() - started >= duration:
                break
            link_index = index % len(links)
            count = counts[link_index]
            count["attempted"] += 1
            try:
                sockets[link_index].send(frames[link_index])
                count["sent"] += 1
            except OSError as error:
                count["errors"] += 1
                if count["errors"] == 1:
                    log_event(f"PROBE_SEND_ERROR interface={links[link_index][0].name} error={error}")
            # Do not catch up with a burst if the VM is scheduled late.
            due = max(due + 1.0 / rate, time.monotonic())
    except Exception as error:
        _failure = error
        log_event(f"PROBE_FAILED error={error}")
    finally:
        for sock in sockets:
            try:
                sock.close()
            except Exception as error:
                _failure = _failure or error
                log_event(f"PROBE_FAILED socket_cleanup={error}")
        for (intf, peer), count in zip(links, counts):
            log_event(f"PROBE_COUNTS interface={intf.name} peer={peer.node.name} "
                      f"attempted={count['attempted']} sent={count['sent']} errors={count['errors']}")
        log_event(f"PROBE_FINISHED elapsed={time.monotonic() - started:.3f}")


def do_attack(net, target: str, *, rate=None, duration=30):
    global _thread, _failure
    if _thread is not None and _thread.is_alive():
        raise RuntimeError("A laboratory probe is already running")
    links = laboratory_links(net, target)
    _failure = None
    if rate is None:
        log_event(f"PROBE_START {target} mode=diagnostic")
        for intf, peer in links:
            sendp(_frame(intf, peer), iface=intf.name, count=1, verbose=False)
            log_event(f"PROBE_SENT {target} interface={intf.name} peer={peer.node.name}")
        return
    if not isinstance(rate, int) or not 2 <= rate <= 100:
        raise ValueError("Aggregate probe rate must be 2-100 packets/s")
    if not isinstance(duration, int) or not 1 <= duration <= 30:
        raise ValueError("Probe duration must be 1-30 seconds")
    _stop.clear()
    log_event(f"PROBE_START {target} mode=sustained rate={rate} duration={duration} "
              f"max_frames={min(rate * duration, 3000)}")
    _thread = threading.Thread(target=_send_bounded, args=(links, rate, duration),
                               name="group8-bounded-probe")
    _thread.start()


def stop_attack(net, target: str):
    global _thread
    _stop.set()
    if _thread is not None:
        _thread.join(timeout=5)
        if _thread.is_alive():
            raise RuntimeError("Probe worker did not stop within five seconds")
        _thread = None
    log_event(f"PROBE_STOP {target}")
    if _failure is not None:
        raise RuntimeError("Laboratory probe failed; inspect attack.log") from _failure
