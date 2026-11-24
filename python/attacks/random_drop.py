import random
import threading

import attacks.common
from attacks.common import apply_loss, log_event

# Set to stop the background thread that keeps changing the loss rate
_stop = threading.Event()
_thread: threading.Thread | None = None


def _change_loss(switch, interval: int):
    while not _stop.is_set():
        loss_pct = random.randint(0, 100)
        apply_loss(switch, loss_pct)
        log_event(f"ATTACK_CHANGE random {switch.name} loss={loss_pct}")
        _stop.wait(interval)


def do_attack(net, target: str, interval: int):
    """
    Random dropping: the loss rate is re-drawn from 0-100% every `interval` seconds,
    so the attack is harder to tell apart from a flaky link.
    """
    global _thread
    switch = net.get(target)
    _stop.clear()

    log_event(f"ATTACK_START random {switch.name} interval={interval}s")
    _thread = threading.Thread(target=_change_loss, args=(switch, interval),
                               daemon=True)
    _thread.start()


def stop_attack(net, target: str):
    _stop.set()
    if _thread is not None:
        _thread.join()
    attacks.common.stop_attack(net, target, "random")
