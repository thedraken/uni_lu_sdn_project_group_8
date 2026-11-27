#!/usr/bin/env python

import sys

from sdn_setup.redirect_scenario import run_redirect_scenario

if __name__ == "__main__":
    #Pass "baseline" to run the same timeline with no attack
    attack = not (len(sys.argv) > 1 and sys.argv[1].lower() == "baseline")
    run_redirect_scenario(attack)
