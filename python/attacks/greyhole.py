from attacks.common import apply_loss, log_event

"""
Partial dropping: only loss_pct% of transit packets are discarded.
By default, is set to 50
"""


def do_attack(net, target: str, loss_pct: int = 50):
    switch = net.get(target)
    apply_loss(switch, loss_pct)
    log_event(f"ATTACK_START greyhole {switch.name} loss={loss_pct}")
