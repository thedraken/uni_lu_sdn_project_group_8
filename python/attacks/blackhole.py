from attacks.common import apply_loss, log_event

"""
Complete dropping: every transit packet through the switch is discarded.
"""
def do_attack(net, target: str):
    switch = net.get(target)
    apply_loss(switch, 100)
    log_event(f"ATTACK_START blackhole {switch.name} loss=100")
