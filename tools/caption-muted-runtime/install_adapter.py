"""Fixed owned-emulator install using the retained verified APK descriptor.

Android16 PackageManagerShellCommand accepts '-S <bytes> -' from stdin.
Official adb37.0.1 shell -T preserves binary stdin through shell-v2 framing.
No mutable APK pathname, runtime permission grant, session daemon or CLI input
is introduced. A failed or interrupted attempt remains installation-uncertain.
"""
import os
import re
from runtime_contract import Blocked,PORT,SERIAL,PACKAGE

# Only these fixed official categories may leave this module; never matched text.
_PACKAGE_REJECTION=re.compile(r'Failure \[INSTALL_(?:FAILED_(?:INVALID_APK|INSUFFICIENT_STORAGE|UPDATE_INCOMPATIBLE|OLDER_SDK|NO_MATCHING_ABIS|USER_RESTRICTED|TEST_ONLY|VERIFICATION_FAILURE|VERSION_DOWNGRADE)|PARSE_FAILED_(?:NO_CERTIFICATES|BAD_MANIFEST|UNEXPECTED_EXCEPTION))(?:[:\]])')

def rejection_category(result):
    text=result.stdout+'\n'+result.stderr
    if _PACKAGE_REJECTION.search(text):return 'package_rejected'
    if any(token in text.lower() for token in ('device offline','no devices/emulators found',"device 'emulator-5556' not found",'transport error','protocol fault')):return 'transport_unavailable'
    if result.returncode==0:return 'output_contract'
    return 'unknown'

def install_verified(run,artifact):
    if run.server.poll() is not None or run.emulator.poll() is not None:
        raise Blocked('OWNED_ADB_OR_EMULATOR_EXITED')
    artifact.recheck()
    size=artifact.identity['signed_size']
    if type(size) is not int or not 0<size<=16*1024*1024:
        raise Blocked('INSTALL_STREAM_SIZE_UNVERIFIED')
    fd=os.dup(artifact._file.fd)  # Duplicate the retained inode, never reopen a pathname.
    argv=[run.adb,'-P',PORT,'-s',SERIAL,'shell','-T','cmd','package','install',
          '--user','0','--pkg',PACKAGE,'-S',str(size),'-']
    run.result.update(install_exit_code=None,install_rejection='unknown')
    try:
        os.lseek(fd,0,os.SEEK_SET)
        result=run.command(argv,timeout=45,max_output_bytes=4096,stdin_fd=fd,stdin_bytes=size)
        code=result.returncode
        run.result.update(install_exit_code=code if type(code) is int and -255<=code<=255 else None,install_rejection=rejection_category(result))
        consumed=os.lseek(fd,0,os.SEEK_CUR)
    finally:
        os.close(fd)
        artifact.recheck()
    if type(result.returncode) is not int or result.returncode!=0:
        raise Blocked('INSTALL_STREAM_COMMAND_FAILED')
    if result.stderr!='':
        raise Blocked('INSTALL_STREAM_STDERR_UNEXPECTED')
    # Raw (-T), not PTY, so Android PrintWriter.println emits exactly one LF.
    if result.stdout!='Success\n':
        raise Blocked('INSTALL_STREAM_SUCCESS_UNVERIFIED')
    if consumed!=size:
        raise Blocked('INSTALL_STREAM_INPUT_INCOMPLETE')
    run.result.pop('install_exit_code',None);run.result.pop('install_rejection',None)
