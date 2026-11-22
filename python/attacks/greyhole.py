from mininet.log import info


def do_attack(net, target: str, loss_pct: int):
    """Drop loss_pct% of the transit packets on the target switch."""
    switch = net.get(target)
    info(f"Doing greyhole attack on {switch.name} at {loss_pct}% loss\n")
    #TODO install the netem loss on the switch's transit interfaces
