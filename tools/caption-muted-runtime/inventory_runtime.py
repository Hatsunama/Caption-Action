#!/usr/bin/env python3
"""UNPUBLISHED: one no-grant RECORD_AUDIO UI inventory. No capture mode.

Root approved this exact one-dialog/no-grant scope and immutable asset binding.
Execution still requires the separately reviewed organization workflow/commit.
"""
import argparse, hashlib, io, json, os, pathlib, re, shutil, signal, subprocess, tarfile, tempfile, urllib.request
import xml.etree.ElementTree as ET
import artifact_gate, boot_probe as boot, runtime_contract as c, isolation, guest_transport, failure_diagnostics, install_adapter
from capture_runtime import CaptureRun
from safety_watchdog import run_bounded, owned_emulator_identity
from boot_probe import Blocked
HERE=pathlib.Path(__file__).resolve().parent
ROOT_INVENTORY_REVIEW={'mode': 'record-audio-inventory', 'scope': 'own-dialog-no-grant-no-projection-no-capture', 'source_parent': '55d7d6b64b0a74589f891fd5e0bdf91b52377c16', 'signed_sha256': '43e88278a61b5209aebf477ff1029aa2804c1a118b93e362b7dd9877334448cc', 'unsigned_sha256': 'dda71638acd519f8ec37c6a31738c63c4ffda664bdfff33e0003c5e7fb63761f', 'source_sha256': '228b4ddd5290e236111d6fdec93a6c0fe6dccd3d32cd14c34092d914eb54384c', 'receipt_sha256': 'e6f4917b4650d750582dd012edc2969c413cc71b209ddfc554b72a75dc55f60b'}
DOWNLOAD_MANIFEST=[{'url': 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/8a976164b509ba5403bb3f371d88733ef19bf70b/diagnostics/capture-probe-delivery/20261013/capture-probe-signed-diagnostic.apk', 'sha256': '43e88278a61b5209aebf477ff1029aa2804c1a118b93e362b7dd9877334448cc', 'size': 3712656, 'name': 'signed.apk'}, {'url': 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/8a976164b509ba5403bb3f371d88733ef19bf70b/diagnostics/capture-probe-delivery/20261013/capture-probe-unsigned.apk', 'sha256': 'dda71638acd519f8ec37c6a31738c63c4ffda664bdfff33e0003c5e7fb63761f', 'size': 3701460, 'name': 'unsigned.apk'}, {'url': 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/8a976164b509ba5403bb3f371d88733ef19bf70b/diagnostics/capture-probe-delivery/20261013/capture-probe-source.tar.gz', 'sha256': '228b4ddd5290e236111d6fdec93a6c0fe6dccd3d32cd14c34092d914eb54384c', 'size': 17101, 'name': 'source.tar.gz'}, {'url': 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/8a976164b509ba5403bb3f371d88733ef19bf70b/diagnostics/capture-probe-delivery/20261013/PUBLIC_DIAGNOSTIC_VERIFICATION.json', 'sha256': 'e6f4917b4650d750582dd012edc2969c413cc71b209ddfc554b72a75dc55f60b', 'size': 1104, 'name': 'receipt.json'}]
EXISTING_C_CERT='2a0d37dc62eebed39bd08a9238c9255e26eb52a00143a02e0c6c473489985a21'
SOURCE_HASHES={
 'AndroidManifest.xml':'125fafd6117d18ad436eaccb7df6508b364915f34585efa8e23bf02e27e92fca',
 'ProbeActivity.kt':'6b031f2f4efcc8707eb15e25e043d140320582ded1a72db31ded64e713d2f35c',
 'ProbeService.kt':'d5cc560d577769296d8e3b4f70b954d7920e98e7f20c1c1858b5df415251e477',
 'ProbeRoute.kt':'3ab1236ca2f8b6975c25471e834d8cfbc09c4f8cbbb8861ea1872f430a55c034',
 'ProbeFinalization.kt':'9da4c5c0ac9fe6644e0098e8f392e5876bdfc9e11ab624514c864cddf868e96b'}
FILES={'signed.apk':'signed_sha256','unsigned.apk':'unsigned_sha256','source.tar.gz':'source_sha256','receipt.json':'receipt_sha256'}

def require_binding():
    ident=artifact_gate.require_approved_identity(artifact_gate.DELIVERY_PARENT)
    if (ident['unsigned_sha256']!=isolation.UNSIGNED_SHA256 or ident['signer_cert_sha256']!=EXISTING_C_CERT or
        not isinstance(ROOT_INVENTORY_REVIEW,dict) or set(ROOT_INVENTORY_REVIEW)!={'mode','scope','source_parent','signed_sha256','receipt_sha256','source_sha256','unsigned_sha256'} or
        ROOT_INVENTORY_REVIEW.get('mode')!='record-audio-inventory' or
        ROOT_INVENTORY_REVIEW.get('scope')!='own-dialog-no-grant-no-projection-no-capture' or
        not re.fullmatch(r'[0-9a-f]{40}',ROOT_INVENTORY_REVIEW.get('source_parent','')) or
        any(ROOT_INVENTORY_REVIEW[k]!=ident[k] for k in FILES.values())):
        raise Blocked('NEW_SIGNED_RECEIPT_AND_ROOT_INVENTORY_REVIEW_REQUIRED')
    if type(DOWNLOAD_MANIFEST) is not list or len(DOWNLOAD_MANIFEST)!=4:
        raise Blocked('EXACT_DOWNLOAD_MANIFEST_UNBOUND')
    seen=set()
    for row in DOWNLOAD_MANIFEST:
        if type(row) is not dict or set(row)!={'url','sha256','size','name'}:raise Blocked('DOWNLOAD_MANIFEST_SCHEMA')
        name=row['name'];url=row['url']
        if name not in FILES or name in seen or row['sha256']!=ident[FILES[name]]:raise Blocked('DOWNLOAD_IDENTITY_MISMATCH')
        seen.add(name)
        if type(row['size']) is not int or not 0<row['size']<=64*1024*1024:raise Blocked('DOWNLOAD_SIZE_UNBOUND')
        if not re.fullmatch(r'https://raw\.githubusercontent\.com/Hatsunama/Caption-Action/[0-9a-f]{40}/[A-Za-z0-9_./-]+',url) or '/..' in url:raise Blocked('DOWNLOAD_ORIGIN_UNREVIEWED')
    return ident

def preflight():
    ident=require_binding()  # BEFORE policy/checkout/SDK/network/guest activity.
    boot.policy(os.environ);boot.select_cell(os.environ.get('PROBE_CELL',''))
    actual=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    parents=subprocess.check_output(['git','show','-s','--format=%P','HEAD'],text=True).strip()
    origin=subprocess.check_output(['git','config','--get','remote.origin.url'],text=True).strip()
    if origin not in ('https://github.com/'+boot.RUNTIME_SOURCE_REPOSITORY, 'https://github.com/'+boot.RUNTIME_SOURCE_REPOSITORY+'.git'):
        raise Blocked('RUNTIME_SOURCE_REPOSITORY_MISMATCH')
    if actual!=os.environ.get('PROBE_COMMIT') or parents!=ROOT_INVENTORY_REVIEW['source_parent']:
        raise Blocked('EXACT_CHECKOUT_OR_PARENT_MISMATCH')
    return ident

def staging():
    return pathlib.Path(os.environ['RUNNER_TEMP']).resolve(strict=True)/'caption-record-artifacts'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise Blocked('ARTIFACT_DOWNLOAD_REDIRECT_FORBIDDEN')

def download_exact():
    preflight();dest=staging();dest.mkdir(mode=0o700,exist_ok=False)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    try:
        for row in DOWNLOAD_MANIFEST:
            with opener.open(row['url'],timeout=30) as response:
                if response.status!=200:raise Blocked('ARTIFACT_DOWNLOAD_STATUS')
                data=response.read(row['size']+1)
            if len(data)!=row['size'] or hashlib.sha256(data).hexdigest()!=row['sha256']:raise Blocked('ARTIFACT_DOWNLOAD_BYTES_MISMATCH')
            with (dest/row['name']).open('xb') as f:f.write(data)
    except BaseException:
        shutil.rmtree(dest);raise

def source_binding(artifact):
    artifact.recheck()
    with artifact_gate.CheckedFile(staging()/'source.tar.gz',artifact_gate.MAX_SOURCE_BYTES) as checked:
        blob=checked.read()
    if hashlib.sha256(blob).hexdigest()!=artifact.identity['source_sha256']:raise Blocked('SOURCE_ARCHIVE_CHANGED')
    found={}
    with tarfile.open(fileobj=io.BytesIO(blob),mode='r:gz') as archive:
        members=archive.getmembers()
        if len(members)>2000 or sum(m.size for m in members)>32*1024*1024:raise Blocked('SOURCE_ARCHIVE_LIMIT')
        for member in members:
            if member.issym() or member.islnk() or member.name.startswith('/') or '..' in pathlib.PurePosixPath(member.name).parts:raise Blocked('SOURCE_ARCHIVE_UNSAFE')
            name=pathlib.PurePosixPath(member.name).name
            if name in SOURCE_HASHES and member.isfile():
                if name in found or member.size>256*1024:raise Blocked('SOURCE_ARCHIVE_AMBIGUOUS')
                found[name]=archive.extractfile(member).read()
    if set(found)!=set(SOURCE_HASHES) or any(hashlib.sha256(found[n]).hexdigest()!=h for n,h in SOURCE_HASHES.items()):raise Blocked('REVIEWED_SOURCE_FILES_MISMATCH')
    source=found['ProbeService.kt'].decode();manifest=found['AndroidManifest.xml'].decode()
    if (source.count('.addMatchingUid(')!=1 or '.addMatchingUid(Process.myUid())' not in source or
        source.count('.addMatchingUsage(')!=1 or '.addMatchingUsage(AudioAttributes.USAGE_MEDIA)' not in source or
        'setAudioSource(' in source or 'AudioSource.MIC' in source or 'createVirtualDisplay' in source or
        any(x in manifest for x in ('sharedUserId','android.permission.INTERNET','android.permission.CAMERA'))):raise Blocked('SOURCE_FILTER_SCOPE_MISMATCH')
    # Exact reviewed source and exact frozen unsigned payload mapping are both
    # root-bound above, and the live signed payload is independently unchanged.
    return {'artifact_source_bound':True,'unsigned_sha256':isolation.UNSIGNED_SHA256,
            'uid_rules':['Process.myUid()'],'usage_rules':['USAGE_MEDIA'],'mic_fallback':False,
            'shared_user_id':False,'internet':False,'camera':False,'virtual_display':False}

def verify_artifact():
    preflight();root=staging()
    return artifact_gate.verify_artifacts(delivery_parent=artifact_gate.DELIVERY_PARENT,
        signed_apk=root/'signed.apk',unsigned_apk=root/'unsigned.apk',source_archive=root/'source.tar.gz',
        receipt_path=root/'receipt.json',android_sdk_root=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME',''),runner=run_bounded)

class InventoryRun(CaptureRun):
    def __init__(self,*args,binding,**kwargs):
        super().__init__(*args,**kwargs);self.binding=binding;self.uid=None;self.filtered_xml=None
        self.result.update(scope='record-audio-inventory-only',runtime_pass=False,capture_started=False,permissions_granted=False)
    def before_guest_launch(self):
        guest_transport.require_ports_absent(*guest_transport.tables())
    def verify_guest_transport(self):
        if self.emulator.poll() is not None:raise Blocked('OWN_EMULATOR_EXITED')
        current=owned_emulator_identity(self.emulator.pid)
        if self.guest_identity is None:self.guest_identity=current
        if current!=self.guest_identity:raise Blocked('OWN_EMULATOR_CHANGED')
        return guest_transport.verify_endpoints(*guest_transport.tables(),guest_transport.owned_sockets(current))
    def command(self,argv,**kwargs):
        if getattr(self,'adb',None) and argv and argv[0]==self.adb:
            if not self.verify_guest_transport():raise Blocked('OWN_GUEST_TRANSPORT_NOT_READY')
        return super().command(argv,**kwargs)
    def cleanup_command(self,argv,**kwargs):
        if not self.verify_guest_transport():raise Blocked('CLEANUP_TRANSPORT_NOT_OWNED')
        return run_bounded(argv,env=self.env,**kwargs)
    def adb_command(self,args,**kwargs):
        if not self.verify_guest_transport():raise Blocked('OWN_GUEST_TRANSPORT_NOT_READY')
        return super().adb_command(args,**kwargs)
    def execute_capture(self,*args):raise Blocked('CAPTURE_DISABLED_IN_INVENTORY')
    def execute_discovery(self,*args):raise Blocked('OTHER_DISCOVERY_MODES_DISABLED')
    def tap(self,spec):
        if spec!=c.APP_BEGIN or getattr(self,'begin_attempted',False):raise Blocked('ONLY_ONE_OWN_BEGIN_ALLOWED')
        self.begin_attempted=True
        return super().tap(spec)
    def assert_idle(self,diagnostics_only=False):
        if diagnostics_only:raise Blocked('LEGACY_IDLE_DIAGNOSTIC_DISABLED')
        ident=owned_emulator_identity(self.emulator.pid)
        if ident!=self.guest_identity:raise Blocked('OWNED_EMULATOR_IDENTITY_CHANGED')
        # New independent audio getter is read-only and bypasses legacy single
        # setup sequence guard without permitting another volume write.
        r=self.command([self.adb,'-P',c.PORT,'-s',c.SERIAL,'shell','cmd','audio','get-stream-volume','3'],timeout=5)
        if r.returncode or r.stderr:raise Blocked('INDEPENDENT_AUDIO_READ_FAILED')
        av=c.parse_audio_volume('get-stream-volume',r.stdout)
        mv=c.parse_volume(self.shell(['cmd','media_session','volume','--stream','3','--get']))
        c.verify_projection_idle(self.shell(['dumpsys','media_projection']))
        rows=isolation.parse_package_uid_rows(self.shell(['pm','list','packages','-U','--user','0']))
        if c.verify_package(self.shell(['dumpsys','package',c.PACKAGE]),permission=False)!=self.uid:raise Blocked('PROBE_UID_CHANGED')
        self.result['isolation']=isolation.verify_probe_isolation(self.shell(['dumpsys','audio']),
            self.shell(['dumpsys','media.audio_policy']),self.shell(['dumpsys','media_session']),uid=self.uid,
            package_uid_rows=rows,binding=self.binding,owned_emulator=True,audio_volume=av,media_volume=mv)
    def execute_inventory(self,artifact):
        boot.Probe.execute(self)
        self.result.update(status='blocked',runtime_pass=False)
        self.stage='inventory-transport'
        if not self.verify_guest_transport():raise Blocked('OWN_GUEST_TRANSPORT_NOT_READY')
        self.stage='inventory-avd-name'
        avd=self.adb_command(['emu','avd','name'])
        if avd!='caption_muted_probe\nOK':raise Blocked('OWN_AVD_NAME_MISMATCH')
        self.stage='inventory-locale-display'
        locale=self.shell(['getprop','persist.sys.locale']) or self.shell(['getprop','ro.product.locale'])
        if locale!='en-US' or self.shell(['wm','size'])!='Physical size: 480x800':raise Blocked('UNREVIEWED_LOCALE_OR_DISPLAY')
        self.stage='inventory-package-absence'
        if self.shell(['pm','list','packages',c.PACKAGE]):raise Blocked('PROBE_ALREADY_INSTALLED_NOT_FRESH')
        self.verified_mute_setup()
        self.stage='inventory-projection-idle'
        c.verify_projection_idle(self.shell(['dumpsys','media_projection']))
        # Installation does not launch the app. UID isolation cannot be claimed
        # until installed identity and the complete UID inventory are verified.
        self.stage='inventory-artifact-recheck'
        artifact.recheck();self.installed=True
        self.stage='inventory-install'
        install_adapter.install_verified(self,artifact)
        self.stage='inventory-artifact-recheck'
        artifact.recheck()
        self.stage='inventory-installed-identity'
        self.uid=c.verify_package(self.shell(['dumpsys','package',c.PACKAGE]),stopped=True,permission=False)
        c.verify_service_stopped(self.shell(['dumpsys','activity','services',c.PACKAGE]))
        self.stage='inventory-isolation'
        self.assert_idle()
        self.stage='inventory-watchdog-arm'
        self.arm_watchdog()  # Before any own Activity UI action.
        self.stage='inventory-activity-start'
        self.shell(['am','start','-W','-n',c.ACTIVITY],seconds=8)
        self.stage='inventory-process-identity'
        pid=self.shell(['pidof',c.PACKAGE]);ps=self.shell(['ps','-A','-o','UID,PID,NAME'])
        rows=[l.split() for l in ps.splitlines()[1:] if l.split() and l.split()[-1]==c.PACKAGE]
        if not re.fullmatch(r'[1-9][0-9]{1,6}',pid) or rows!=[[str(self.uid),pid,c.PACKAGE]]:raise Blocked('OWN_PROCESS_IDENTITY_UNKNOWN')
        self.stage='inventory-isolation'
        self.assert_idle()
        self.stage='inventory-begin'
        self.tap(c.APP_BEGIN)
        self.stage='inventory-dialog-inspection'
        facts=c.inspect_consent_dialog(*self.snapshot_ui(),'discover-record')
        if facts['kind']!='record-audio' or sum(n['text_key']=='probe-record-audio-request' for n in facts['allowlisted_nodes'])!=1:raise Blocked('UNEXPECTED_DIALOG_KIND_OR_REQUEST')
        self.result['consent_dialog']=facts
        self.stage='inventory-dialog-serialization'
        self.filtered_xml=filtered_structure(facts)
        self.stage='inventory-post-dialog-checks'
        c.verify_package(self.shell(['dumpsys','package',c.PACKAGE]),permission=False)
        c.verify_service_stopped(self.shell(['dumpsys','activity','services',c.PACKAGE]))
        self.assert_idle()
        self.stage='inventory-force-stop'
        self.force_stopped()
        self.stage='inventory-watchdog-disarm'
        self.disarm_watchdog()
        self.stage='inventory-uninstall'
        if self.adb_command(['uninstall',c.PACKAGE],seconds=10)!='Success' or self.shell(['pm','list','packages',c.PACKAGE]):raise Blocked('OWN_UNINSTALL_UNVERIFIED')
        self.installed=False
        self.result.update(status='discovery-stopped-pending-host-cleanup',teardownVerified=True)

def filtered_structure(facts):
    # Deliberately NOT raw hierarchy: only owner-verified allowlisted facts.
    root=ET.Element('filtered-consent-structure',{'owner':facts['owner'],'component':facts['foreground_component'],'raw-hierarchy':'false'})
    for node in facts['allowlisted_nodes']:
        ET.SubElement(root,'node',{k:json.dumps(v) if isinstance(v,(list,bool)) else str(v) for k,v in node.items()})
    return ET.tostring(root,encoding='unicode')

def write_evidence(result,filtered=None):
    dest=pathlib.Path(os.environ['RUNNER_TEMP']).resolve(strict=True)/'caption-record-inventory'
    dest.mkdir(mode=0o700,exist_ok=False)
    failure_diagnostics.validate_public_failure(result)
    blobs={'inventory.json':json.dumps(result,sort_keys=True).encode()}
    if filtered is not None:blobs['consent-structure.xml']=filtered.encode()
    if any(len(b)>256*1024 for b in blobs.values()) or sum(map(len,blobs.values()))>=4*1024*1024:raise Blocked('PUBLISH_EVIDENCE_LIMIT')
    for name,blob in blobs.items():
        with (dest/name).open('xb') as f:f.write(blob)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--preflight-only',action='store_true');parser.add_argument('--binding-only',action='store_true');parser.add_argument('--download-only',action='store_true');args=parser.parse_args()
    if args.binding_only and not args.preflight_only or args.download_only and args.preflight_only:raise Blocked('UNKNOWN_MODE_COMBINATION')
    preflight()
    if args.binding_only:return 0
    if args.download_only:download_exact();return 0
    with verify_artifact() as artifact:
        binding=source_binding(artifact)
        if args.preflight_only:return 0
        temp_root=pathlib.Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        evidence=pathlib.Path(tempfile.mkdtemp(prefix='caption-inventory-private-',dir=temp_root))
        run=InventoryRun(evidence,temp_root,binding=binding)
        def interrupted(*_args):raise Blocked('INTERRUPTED')
        signal.signal(signal.SIGINT,interrupted);signal.signal(signal.SIGTERM,interrupted)
        try:run.execute_inventory(artifact)
        except Exception as error:
            run.result.update(status='BLOCKED',**failure_diagnostics.describe(error,run.stage))
        finally:
            try:run.cleanup()
            except Exception:run.result.update(status='cleanup-failed',runtime_pass=False)
            shutil.rmtree(evidence)
        if run.result['status']=='DISCOVERY_STOPPED':run.result['status']='INVENTORY_STOPPED'
        good=run.result['status']=='INVENTORY_STOPPED'
        # Failure keeps only non-content scalars; no raw dumps/logs/URLs/paths.
        result={k:run.result[k] for k in ('status','scope','runtime_pass','capture_started','permissions_granted','teardownVerified','reason','cleanup_errors','failure_stage','failure_operation','failure_errno','install_exit_code','install_rejection') if k in run.result}
        if good:result.update(consent_dialog=run.result['consent_dialog'],isolation=run.result['isolation'])
        write_evidence(result,run.filtered_xml if good else None)
        print(json.dumps(result,sort_keys=True));return 0 if good else 1
if __name__=='__main__':
    try:raise SystemExit(main())
    except (Blocked,artifact_gate.ArtifactGateError) as error:
        print(json.dumps({'status':'BLOCKED','runtime_pass':False,**failure_diagnostics.describe(error,'preflight')}));raise SystemExit(1)
