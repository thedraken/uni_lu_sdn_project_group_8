from datetime import datetime

from mininet.log import info
from mininet.node import Switch

ATTACK_LOG = "output/attack.log"


def log_event(message: str):
    """
    Logs the event during the attacks
    """
    line = f"{datetime.now().isoformat(timespec='milliseconds')} {message}"
    info(line + "\n")
    with open(ATTACK_LOG, "a") as f:
        f.write(line + "\n")


def switch_facing_interfaces(switch) -> list[str]:
    """
    Interfaces of this switch whose other end is another switch.
    Host-facing ports are left alone, so only transit traffic is attacked.
    """
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


def apply_loss(switch, loss_pct: int):
    """
    Drop loss_pct% of packets leaving the switch towards other switches.
    `netem` works below OVS, so ONOS cannot see or remove it, as a compromised switch would behave.
    """
    for name in switch_facing_interfaces(switch):
        out = switch.cmd(
            f"tc qdisc replace dev {name} root netem loss {loss_pct}%")
        if out.strip():
            info(f"tc on {name}: {out}")


def clear_loss(switch):
    for name in switch_facing_interfaces(switch):
        # Errors if nothing is installed, which is fine
        switch.cmd(f"tc qdisc del dev {name} root 2>/dev/null")


def stop_attack(net, target: str, attack_name: str):
    """
    Stops the dropping process, logs the event
    """
    switch = net.get(target)
    clear_loss(switch)
    log_event(f"ATTACK_STOP {attack_name} {switch.name}")
