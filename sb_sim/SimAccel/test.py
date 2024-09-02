#!/usr/bin/env python3

# Example illustrating how to interact with the umi_fifo module

# Copyright (c) 2024 Zero ASIC Corporation
# This code is licensed under Apache License 2.0 (see LICENSE for details)

import sys
import random
from pathlib import Path
import numpy as np

from switchboard import SbDut

PROJ_DIR = Path(__file__).resolve().parent.parent.parent

def chisel_generated_sources(topModule_name):
    """Reads chisel generated filelist.f and returns list of source files"""
    dir = str(PROJ_DIR)+'/generated_sv_dir/'+topModule_name
    filename = dir+'/filelist.f'
    hdl_sources = []
    with open(filename, 'r') as f:
        lines = f.readlines()

    return list(map(lambda x: dir + '/' + x.strip('\n'), lines))

def main():
    # build the simulator
    dut = build_testbench()

    # wire up max-beats argument
    dut.intf_defs['hostMem']['max_beats'] = dut.args.max_beats
    dut.intf_defs['hostCtrl']['max_beats'] = 1
    print(dut.intf_defs)
    print(f'maxbeats:{dut.args.max_beats}')

    # launch the simulation
    dut.simulate()

    # run the test: write to random addresses and read back in a random order

    hostMem  = dut.intfs['hostMem']
    hostCtrl = dut.intfs['hostCtrl']


    srcbaseAddr = 0x10000
    dstbaseAddr = 0x21000
    length      = 32

    model = np.zeros((length,), dtype=np.uint8)

    success = True
    baseAddr = 0x1_0000

    for i in range(length>>2):
        addr = srcbaseAddr + (i * 4)
        data = np.random.randint(0, 255, size=4, dtype=np.uint8)
        print(f"Write Data:{addr}@{data}")
        model[(i*4): (i+1) * 4] = data
        hostMem.write(addr, data)

    configBaseAddr = 0x20000
    hostCtrl.write(configBaseAddr  , np.array(list(srcbaseAddr.to_bytes(4, byteorder="little")),dtype=np.uint8))
    hostCtrl.write(configBaseAddr+8, np.array(list(dstbaseAddr.to_bytes(4, byteorder="little")),dtype=np.uint8))
    hostCtrl.write(configBaseAddr+16, np.array(list(length.to_bytes(4, byteorder="little")),dtype=np.uint8))
    hostCtrl.write(configBaseAddr+24, np.zeros(4, dtype=np.uint8))

    done = False

    while not(done):
        data = hostCtrl.read(configBaseAddr+32, 4)
        done = data[0] != 0


    for i in range(length>>2):
        addr = dstbaseAddr + (i * 4)
        data = hostCtrl.read(addr, 4)
        print(f"Read Data:{addr}@{data}")
        # check against the model
        if not np.array_equal(data, model[i*4:(i+1) * 4]):
            print('MISMATCH')
            success = False

    if success:
        print("PASS!")
        sys.exit(0)
    else:
        print("FAIL")
        sys.exit(1)


def build_testbench():

    parameters = dict(
        HOSTMEM_DATA_WIDTH=32,
        HOSTMEM_ADDR_WIDTH=18,
        HOSTMEM_ID_WIDTH=2,
        HOSTCTRL_DATA_WIDTH=32,
        HOSTCTRL_ADDR_WIDTH=18,
        HOSTCTRL_ID_WIDTH=2,
    )

    interfaces = {
        'hostMem': dict(type='axi', dw=parameters['HOSTMEM_DATA_WIDTH'], aw=parameters['HOSTMEM_ADDR_WIDTH'], idw=parameters['HOSTMEM_ID_WIDTH'], direction='subordinate'),
        'hostCtrl': dict(type='axi', dw=parameters['HOSTCTRL_DATA_WIDTH'], aw=parameters['HOSTCTRL_ADDR_WIDTH'], idw=parameters['HOSTCTRL_ID_WIDTH'], direction='subordinate')
    }

    resets = [dict(name='rst', delay=0)]

    extra_args = {
        '-n': dict(type=int, default=10000, help='Number of'
        ' words to write as part of the test.'),
        '--max-bytes': dict(type=int, default=4, help='Maximum'
        ' number of bytes in any single read/write.'),
        '--max-beats': dict(type=int, default=1, help='Maximum'
        ' number of beats to use in AXI transfers.')
    }

    dut = SbDut('SimAccelTop', autowrap=True, cmdline=True, extra_args=extra_args,
        parameters=parameters, interfaces=interfaces, resets=resets)

    for src_file in chisel_generated_sources('accelShell.sim.SimAccel'):
        dut.input(src_file)

    dut.input(PROJ_DIR / 'src' / 'main' / 'resources' / 'vsrc' / 'SimAccelTop.sv')

    dut.add('tool', 'verilator', 'task', 'compile', 'warningoff',
        ['WIDTHEXPAND', 'CASEINCOMPLETE', 'WIDTHTRUNC', 'TIMESCALEMOD'])

    dut.build(fast=True)

    return dut


if __name__ == '__main__':
    main()
