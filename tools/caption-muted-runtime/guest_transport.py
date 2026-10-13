"""Bind emulator-5556 to this owned process group before any guest command."""
import os,pathlib,re
from boot_probe import Blocked
from safety_watchdog import owned_emulator_identity
PORTS={5556,5557}
def endpoints(ipv4,ipv6):
    rows=[]
    for family,text in ((4,ipv4),(6,ipv6)):
        for line in text.splitlines()[1:]:
            fields=line.split()
            if len(fields)<10:raise Blocked('HOST_SOCKET_TABLE_UNKNOWN')
            try:address,port=fields[1].split(':');port=int(port,16);inode=int(fields[9])
            except ValueError:raise Blocked('HOST_SOCKET_TABLE_UNKNOWN')
            if port in PORTS:rows.append((family,address,port,fields[3],inode))
    return rows

def require_ports_absent(ipv4,ipv6):
    if endpoints(ipv4,ipv6):raise Blocked('EMULATOR_PORTS_ALREADY_IN_USE')

def verify_endpoints(ipv4,ipv6,owned_socket_inodes):
    rows=endpoints(ipv4,ipv6)
    listening=[r for r in rows if r[3]=='0A']
    if not listening:return False
    # Both endpoints must belong to the new guest; no wildcard/external binds.
    if any((f==4 and a!='0100007F') or (f==6 and a!='00000000000000000000000001000000') or inode not in owned_socket_inodes for f,a,p,s,inode in listening):
        raise Blocked('EMULATOR_ENDPOINT_NOT_OWNED_LOOPBACK')
    if {r[2] for r in listening}!=PORTS:return False
    return True

def tables():
    return tuple(pathlib.Path('/proc/net/'+name).read_text() for name in ('tcp','tcp6'))

def owned_sockets(identity):
    if owned_emulator_identity(identity['pid'])!=identity:raise Blocked('EMULATOR_IDENTITY_CHANGED')
    inodes=set()
    for entry in pathlib.Path('/proc').iterdir():
        if not entry.name.isdigit():continue
        try:
            raw=(entry/'stat').read_text();fields=raw[raw.rindex(')')+2:].split()
            if int(fields[2])!=identity['pid']:continue
            if entry.stat().st_uid!=identity['uid'] or int(fields[3])!=identity['pid'] or int(fields[19])<identity['starttime']:
                raise Blocked('EMULATOR_PROCESS_GROUP_CHANGED')
            for fd in (entry/'fd').iterdir():
                try:target=os.readlink(fd)
                except FileNotFoundError:continue
                m=re.fullmatch(r'socket:\[([0-9]+)\]',target)
                if m:inodes.add(int(m[1]))
        except (FileNotFoundError,ProcessLookupError):continue
    return inodes
