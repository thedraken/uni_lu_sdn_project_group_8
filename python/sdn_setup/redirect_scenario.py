#!/usr/bin/env python3

import base64
import json
import os
import re
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

from mininet.log import info, setLogLevel
from mininet.net import Mininet
from mininet.node import OVSSwitch, RemoteController

ONOS_HOST = "127.0.0.1"
ONOS_PORT = 8181
ONOS_USER = "onos"
ONOS_PASS = "rocks"

NORMAL_SECONDS = 10
ATTACK_SECONDS = 60
POST_SECONDS = 15
AUDIT_EVERY = 2
PING_INTERVAL = 0.5

#High priority so the switch obeys the rogue rule over the normal flows
ROGUE_PRIORITY = 60000

RESULTS_DIR = os.environ.get("REDIRECT_RESULTS", "results/redirect")

_LOG = None


def _now():
    return time.time()


def _stamp(t):
    return datetime.fromtimestamp(t, tz=timezone.utc).isoformat()


def log(msg):
    line = f"{_stamp(_now())}  {msg}"
    info("*** " + line + "\n")
    if _LOG:
        _LOG.write(line + "\n")


def of_id(switch):
    return "of:" + switch.dpid


#Find the port number on the switch that connects to the given host/switch
def of_port(switch, peer):
    conns = switch.connectionsTo(peer)
    if not conns:
        return None
    return switch.ports.get(conns[0][0])


def _auth():
    raw = f"{ONOS_USER}:{ONOS_PASS}".encode()
    return "Basic " + base64.b64encode(raw).decode()


def onos_get(path):
    url = f"http://{ONOS_HOST}:{ONOS_PORT}{path}"
    req = urllib.request.Request(url, headers={"Authorization": _auth()})
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode())


#The flow cookies ONOS thinks it installed on a device
def onos_cookies(device_id):
    cookies = set()
    try:
        data = onos_get("/onos/v1/flows/" + device_id)
    except (urllib.error.URLError, ValueError, OSError):
        return cookies
    for flow in data.get("flows", []):
        try:
            cookies.add(int(flow["id"]))
        except (KeyError, TypeError, ValueError):
            pass
    return cookies


_COOKIE_RE = re.compile(r"cookie=0x([0-9a-fA-F]+)")
_OUTPUT_RE = re.compile(r"actions=.*output:")
_PRIO_RE = re.compile(r"priority=(\d+)")


#Return forwarding flows in the switch that ONOS did not install
def audit_switch(switch):
    known = onos_cookies(of_id(switch))
    dump = switch.cmd(f"ovs-ofctl -O OpenFlow13 dump-flows {switch.name}")
    found = []
    for line in dump.splitlines():
        line = line.strip()
        cm = _COOKIE_RE.search(line)
        om = _OUTPUT_RE.search(line)
        if not cm or not om:
            continue
        cookie = int(cm.group(1), 16)
        pm = _PRIO_RE.search(line)
        prio = int(pm.group(1)) if pm else 0
        if prio <= 0:
            continue
        if cookie != 0 and prio < 1000:
            continue
        found.append(line)
    return found


def file_has_lines(path):
    try:
        with open(path) as fh:
            return any(line.strip() for line in fh)
    except OSError:
        return False


#Build the same topology as main.py, but without the CLI
def build_net():
    net = Mininet(switch=OVSSwitch, build=False)
    c0 = net.addController("c0", controller=RemoteController,
                           ip="127.0.0.1", port=6653)
    hosts = []
    for i in range(10):
        hosts.append(net.addHost(f"h{i}", ip=f"10.0.0.{i + 1}/16"))
    switches = []
    for i in range(3):
        s = net.addSwitch(f"s{i}", protocols="OpenFlow13")
        for old in switches:
            net.addLink(s, old)
        switches.append(s)
    for i, h in enumerate(hosts):
        net.addLink(h, switches[i % len(switches)])
    net.build()
    c0.start()
    for s in switches:
        s.start([c0])
    return net, hosts, switches


def hosts_on(net, switch):
    return [h for h in net.hosts if switch.connectionsTo(h)]


def run(attack=True):
    global _LOG
    os.makedirs(RESULTS_DIR, exist_ok=True)
    _LOG = open(os.path.join(RESULTS_DIR, "events.log"), "w", buffering=1)
    metrics = {"mode": "attack" if attack else "baseline"}
    procs = []
    net = None
    try:
        net, hosts, switches = build_net()

        comp = net.get("s0")
        victims = hosts_on(net, comp)
        sender, intended, wrong = victims[0], victims[1], victims[2]
        neighbours = [s for s in switches if s is not comp]
        wrong_port = of_port(comp, wrong)
        if wrong_port is None:
            raise RuntimeError(f"no port to {wrong.name} on {comp.name}")

        log(f"Compromised switch {comp.name} ({of_id(comp)})")
        log(f"sender {sender.name}={sender.IP()} intended {intended.name}="
            f"{intended.IP()} wrong {wrong.name}={wrong.IP()} port {wrong_port}")
        metrics.update(compromised=of_id(comp), sender=sender.IP(),
                       intended=intended.IP(), wrong=wrong.IP())

        log("Waiting 15s for ONOS to discover the topology")
        time.sleep(15)

        net.pingAll()                   #warm-up so reactive flows install
        base_loss = net.pingAll()
        metrics["baseline_loss_pct"] = base_loss
        log(f"Baseline loss {base_loss:.1f}%")

        ping_log = os.path.join(RESULTS_DIR, "ping_victim.log")
        wrong_log = os.path.join(RESULTS_DIR, "wrong_host_tcpdump.log")
        intended_log = os.path.join(RESULTS_DIR, "intended_host_tcpdump.log")

        procs.append(sender.popen(
            f"ping -i {PING_INTERVAL} -D {intended.IP()} > {ping_log} 2>&1",
            shell=True))
        procs.append(wrong.popen(
            f"tcpdump -i {wrong.name}-eth0 -n -tt -l icmp and host "
            f"{intended.IP()} > {wrong_log} 2>&1", shell=True))
        procs.append(intended.popen(
            f"tcpdump -i {intended.name}-eth0 -n -tt -l icmp and host "
            f"{intended.IP()} > {intended_log} 2>&1", shell=True))

        log(f"Normal traffic for {NORMAL_SECONDS}s")
        time.sleep(NORMAL_SECONDS)

        rogue = (f"priority={ROGUE_PRIORITY},ip,nw_dst={intended.IP()},"
                 f"actions=output:{wrong_port}")

        attack_start = None
        t_audit = None
        t_probe = None
        t_isolate = None
        detected_switch = None
        isolated = False

        if attack:
            attack_start = _now()
            log(f"ATTACK start: redirect {intended.IP()} -> {wrong.name}")

        end = _now() + (ATTACK_SECONDS if attack else NORMAL_SECONDS)
        tick = 0
        while _now() < end:
            if attack:
                comp.cmd(f'ovs-ofctl -O OpenFlow13 add-flow {comp.name} "{rogue}"')

            if tick % AUDIT_EVERY == 0:
                #Method 1: flow audit
                for s in switches:
                    sus = audit_switch(s)
                    if sus and t_audit is None:
                        t_audit = _now()
                        detected_switch = of_id(s)
                        log(f"DETECT flow audit: unknown flow on {s.name} -> {sus[0]}")
                #Method 2: probe, did the wrong host get the victim's traffic
                if t_probe is None and file_has_lines(wrong_log):
                    t_probe = _now()
                    if detected_switch is None:
                        detected_switch = of_id(comp)
                    log(f"DETECT probe: {intended.IP()} traffic seen on {wrong.name}")

                first = min([t for t in (t_audit, t_probe) if t], default=None)
                if first and not isolated:
                    open(os.path.join(RESULTS_DIR, "compromised_flows.txt"), "w").write(comp.cmd(f"ovs-ofctl -O OpenFlow13 dump-flows {comp.name}"))
                    log(f"ISOLATE {comp.name}: disabling neighbour links")
                    for nb in neighbours:
                        net.configLinkStatus(comp.name, nb.name, "down")
                    t_isolate = _now()
                    isolated = True

            time.sleep(1)
            tick += 1

        t_restore = None
        if attack:
            log(f"Attacker removed; flow restoration on {comp.name}")
            comp.cmd(f'ovs-ofctl -O OpenFlow13 del-flows {comp.name} '
                     f'"ip,nw_dst={intended.IP()}"')
            if isolated:
                log(f"Reinstating {comp.name}")
                for nb in neighbours:
                    net.configLinkStatus(comp.name, nb.name, "up")
            t_restore = _now()

        log("Recovery pingAll")
        time.sleep(POST_SECONDS)
        net.pingAll()
        rec_loss = net.pingAll()
        t_recovered = _now()
        metrics["recovery_loss_pct"] = rec_loss
        log(f"Recovery loss {rec_loss:.1f}%")

        if attack and attack_start:
            first = min([t for t in (t_audit, t_probe) if t], default=None)
            metrics["detect_time_s"] = (first - attack_start) if first else None
            metrics["detect_time_audit_s"] = (t_audit - attack_start) if t_audit else None
            metrics["detect_time_probe_s"] = (t_probe - attack_start) if t_probe else None
            metrics["identified_switch"] = detected_switch
            metrics["identified_correct"] = (detected_switch == of_id(comp))
            metrics["isolate_time_s"] = (t_isolate - first) if (t_isolate and first) else None
            metrics["recovery_time_s"] = (t_recovered - t_restore) if t_restore else None

        metrics["detected"] = (t_audit is not None) or (t_probe is not None)
        log("Metrics: " + json.dumps(metrics))
        _write_metrics(metrics)
        return metrics

    finally:
        for p in procs:
            try:
                p.terminate()
            except Exception:
                pass
        if net is not None:
            net.stop()
        if _LOG:
            _LOG.close()


def _write_metrics(metrics):
    keys = list(metrics.keys())
    with open(os.path.join(RESULTS_DIR, "metrics.csv"), "w") as fh:
        fh.write(",".join(keys) + "\n")
        fh.write(",".join(str(metrics[k]) for k in keys) + "\n")


def run_redirect_scenario(attack=True):
    setLogLevel("info")
    return run(attack=attack)
