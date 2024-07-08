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
    print(dut.intf_defs)
    print(f'maxbeats:{dut.args.max_beats}')

    # launch the simulation
    dut.simulate()

    # run the test: write to random addresses and read back in a random order

    axi = dut.intfs['hostMem']

    addr_bytes = (axi.addr_width + 7) // 8

    model = np.zeros((1 << axi.addr_width,), dtype=np.uint8)

    success = True
    baseAddr = 0x1_0000

    for _ in range(dut.args.n):
        addrOffset = random.randint(0, (1 << 16) - 1)
        addr = addrOffset + baseAddr
        size = random.randint(1, min(dut.args.max_bytes, (1 << axi.addr_width) - addr))

        if random.random() < 0.5:
            #########
            # write #
            #########

            data = np.random.randint(0, 255, size=size, dtype=np.uint8)

            print(f'Wrote addr=0x{addr:0{addr_bytes * 2}x} data={data}')
            # perform the write
            axi.write(addr, data)
            print(f'Wrote addr=0x{addr:0{addr_bytes * 2}x} data={data}')

            # update local memory model
            model[addr:addr + size] = data
        else:
            ########
            # read #
            ########

            print(f'Read addr=0x{addr:0{addr_bytes * 2}x} size={size}')
            # perform the read
            data = axi.read(addr, size)
            print(f'Read addr=0x{addr:0{addr_bytes * 2}x} data={data}')

            # check against the model
            if not np.array_equal(data, model[addr:addr + size]):
                print('MISMATCH')
                success = False

    if success:
        print("PASS!")
        sys.exit(0)
    else:
        print("FAIL")
        sys.exit(1)


def build_testbench():
    dw = 32
    aw = 17
    idw = 2

    parameters = dict(
        HOSTMEM_DATA_WIDTH=dw,
        HOSTMEM_ADDR_WIDTH=aw,
        HOSTMEM_ID_WIDTH=idw,
    )

    interfaces = {
        'hostMem': dict(type='axi', dw=dw, aw=aw, idw=idw, direction='subordinate')
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

    dut = SbDut('SimDeviceMemTop', autowrap=True, cmdline=True, extra_args=extra_args,
        parameters=parameters, interfaces=interfaces, resets=resets)

    for src_file in chisel_generated_sources('accelShell.sim.SimDeviceMem'):
        dut.input(src_file)

    dut.input(PROJ_DIR / 'src' / 'main' / 'resources' / 'vsrc' / 'SimDeviceMemTop.sv')

    dut.add('tool', 'verilator', 'task', 'compile', 'warningoff',
        ['WIDTHEXPAND', 'CASEINCOMPLETE', 'WIDTHTRUNC', 'TIMESCALEMOD'])

    dut.build(fast=True)

    return dut


if __name__ == '__main__':
    main()
