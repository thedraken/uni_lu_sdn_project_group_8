from attacks.common import apply_loss, log_event


def do_attack(net, target: str):
    """
    Complete dropping: every transit packet through the switch is discarded.
    """
    switch = net.get(target)
    apply_loss(switch, 100)
    log_event(f"ATTACK_START blackhole {switch.name} loss=100")
