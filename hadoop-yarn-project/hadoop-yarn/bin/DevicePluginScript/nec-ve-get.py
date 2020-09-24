#!/usr/bin/python

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import print_function
import sys
import io
import select
import pyudev
import errno
import argparse
import glob
import os

class VENodeState(object):
    # constants defined in VE driver header ve_drv.h
    _OS_STATE_STR = ['ONLINE', 'OFFLINE', 'INITIALIZING', 'TERMINATING']

    @classmethod
    def _VE_STATE(cls, state):
        if state < 0 or len(cls._VE_STATE_STR) <= state:
            return 'UNKNOWN (%d)' % state
        else:
            return cls._VE_STATE_STR[state]

    @classmethod
    def _OS_STATE(cls, state):
        if state < 0 or len(cls._OS_STATE_STR) <= state:
            return 'UNKNOWN (%d)' % state
        else:
            return cls._OS_STATE_STR[state]

    def __init__(self, veno):
        self._veno = veno
        ctx = pyudev.Context()
        self._dev = pyudev.Device.from_device_file(ctx, '/dev/veslot%d' % veno)
        self._busId = self._dev.find_parent('pci')['PCI_SLOT_NAME'];
        self._devnumber = self._dev.device_number;
        self._osstate = io.open(self._dev.sys_path + '/os_state')

    @classmethod
    def _read_state(cls, f):
        f.seek(0, io.SEEK_SET)
        s = int(f.read())
        return s

    @property
    def _os_state(self):
        return self._read_state(self._osstate)

    def show_state(self):
        print('id=%d, dev=/dev/ve%d, state=%s, busId=%s, major=%s, minor=%s' % (self._veno, \
              self._veno,
              self._OS_STATE(self._os_state), \
              self._busId, \
              os.major(self._devnumber), \
              os.minor(self._devnumber)
              ))

    @classmethod
    def monitor(cls, nodes):
        for n in nodes:
            if type(n) is not cls:
                raise TypeError
            n.show_state()

        class _FileMon(object):
            def __init__(self, obj, f):
                self._f = f
                self.node = obj
            def fileno(self):
                return self._f.fileno()
        m = []
        for n in nodes:
            m.append(_FileMon(n, n._osstate))

        while True:
            try:
                (r, w, e) = select.select([], [], m)
            except KeyboardInterrupt:
                return
            nlist = []
            e.sort(key=lambda n: n.node._veno)
            for n in e:
                if n.node not in nlist:
                    n.node.show_state()
                    nlist.append(n.node)

def find_all_nodes():
    files = glob.glob('/dev/veslot[0-7]')
    files.sort()
    return [int(f[len('/dev/veslot'):]) for f in files]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Show VE state')
    parser.add_argument('nodes', type=int, nargs='*', help='VE node#')
    parser.add_argument('-f', '--follow', action='store_true', \
        help='show when status changes')
    result = parser.parse_args()
    if len(result.nodes) > 0:
        nodes = result.nodes
    else:
        nodes = find_all_nodes()

    if len(nodes) <= 0:
        print('No VE devices found')
        sys.exit(-1)
    if result.follow:
        VENodeState.monitor([VENodeState(n) for n in nodes])
    else:
        for n in nodes:
            VENodeState(n).show_state()
