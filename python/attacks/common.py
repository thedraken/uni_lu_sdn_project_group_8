from datetime import datetime

from mininet.log import info
from mininet.node import Switch

ATTACK_LOG = "output/attack.log"

"""
Logs the event during the attacks
"""


def log_event(message: str):
    line = f"{datetime.now().isoformat(timespec='milliseconds')} {message}"
    info(line + "\n")
    with open(ATTACK_LOG, "a") as f:
        f.write(line + "\n")


"""
Interfaces of this switch whose other end is another switch.
Host-facing ports are left alone, so only transit traffic is attacked.
"""


def switch_facing_interfaces(switch) -> list[str]:
    names = []
    for intf in switch.intfList():
        link = intf.link
        if link is None:
            # The loopback interface has no link
            continue
        other = link.intf2 if link.intf1 == intf else link.intf1
        if isinstance(other.node, Switch):
            names.append(intf.name)
    return names


"""
Drop loss_pct% of packets leaving the switch towards other switches.
`netem` works below OVS, so ONOS cannot see or remove it, as a compromised switch would behave.
"""


def apply_loss(switch, loss_pct: int):
    for name in switch_facing_interfaces(switch):
        out = switch.cmd(
            f"tc qdisc replace dev {name} root netem loss {loss_pct}%")
        if out.strip():
            info(f"tc on {name}: {out}")


def clear_loss(switch):
    for name in switch_facing_interfaces(switch):
        # Errors if nothing is installed, which is fine
        switch.cmd(f"tc qdisc del dev {name} root 2>/dev/null")


"""
Stops the dropping process, logs the event
"""


def stop_attack(net, target: str):
    switch = net.get(target)
    clear_loss(switch)
    log_event(f"ATTACK_STOP {switch.name}")
