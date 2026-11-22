from mininet.log import info


def do_attack(net, target: str):
    """Drop 100% of the transit packets on the target switch."""
    switch = net.get(target)
    info(f"Doing blackhole attack on {switch.name}\n")
    #TODO install the drop on the switch's transit interfaces
