"""Opt-in isolated Paper test of the real console command and HTTP lifecycle."""
import argparse, json, os, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--accept-eula', action='store_true', required=True)
parser.add_argument('--paper', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[4]
root = Path(tempfile.mkdtemp(prefix='plugmodcore-port-test-'))
data = root/'plugins/PlugModCore'; data.mkdir(parents=True)
shutil.copy(repo/'PlugModCore/target/plugmodcore-1.0-SNAPSHOT.jar', root/'plugins')
shutil.copy(args.paper, root/'paper.jar')
token = os.urandom(32).hex()
(data/'core-config.yml').write_text('modules:\n  auto-load-on-startup: false\nweb:\n  enabled: true\n  host: "127.0.0.1"\n  port: 8080\n  access-token: "'+token+'"\n', encoding='utf-8')
(root/'eula.txt').write_text('eula=true\n')
(root/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25584\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\ngenerate-structures=false\n')
checks = []; server = None; log = None
def check(name, condition):
    assert condition, name
    checks.append(name); print('PASS '+name, flush=True)
def wait(fn, timeout=120):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        try:
            if fn(): return
        except (OSError, ValueError): pass
        time.sleep(.2)
    raise AssertionError('Timed out')
def http(port, path='/', auth=True):
    req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',headers={'Authorization':'Bearer '+token} if auth else {})
    try:
        with urllib.request.urlopen(req, timeout=3) as response: return response.status, response.read().decode()
    except urllib.error.HTTPError as error: return error.code, error.read().decode()
def command(text, expected):
    offset=len(logpath.read_text(encoding='utf-8',errors='replace'))
    server.stdin.write(text+'\n'); server.stdin.flush()
    wait(lambda: expected in logpath.read_text(encoding='utf-8',errors='replace')[offset:], 15)
def start(name):
    global server, log, logpath
    logpath=root/name; log=logpath.open('w',encoding='utf-8')
    server=subprocess.Popen([str(Path(os.environ['JAVA_HOME'])/'bin/java.exe'),'-Xms256M','-Xmx1024M','-XX:ActiveProcessorCount=2','-jar','paper.jar','--nogui'],cwd=root,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
    wait(lambda: 'Done (' in logpath.read_text(encoding='utf-8',errors='replace'))
def stop():
    server.stdin.write('stop\n'); server.stdin.flush(); server.wait(timeout=60); log.close()
try:
    start('first.log')
    check('default port serves dashboard', http(8080)[0]==200)
    command('module webui port','WebUI: http://127.0.0.1:8080')
    check('query reports actual port', True)
    for value in ['0','65536','-1','abc','1.5']:
        command('module webui port '+value,'Port must be an integer from 1 to 65535')
        check('invalid input rejected: '+value,http(8080)[0]==200)
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1',0)); occupied.listen()
        command('module webui port '+str(occupied.getsockname()[1]),'Previous listener/configuration preserved')
        check('occupied port preserves listener and configuration',http(8080)[0]==200 and 'port: 8080' in (data/'core-config.yml').read_text())
    with socket.socket() as available:
        available.bind(('127.0.0.1',0)); newport=available.getsockname()[1]
    command('module webui port '+str(newport),'Port saved for restart')
    check('new listener serves dashboard',http(newport)[0]==200)
    check('API accessible on new listener',http(newport,'/api/modules')[0]==200)
    check('shared static assets accessible',http(newport,'/web/minecraft-text-editor.js')[0]==200)
    check('authentication preserved',http(newport,auth=False)[0]==401)
    request=urllib.request.Request(f'http://127.0.0.1:{newport}/modules/upload',data=b'',headers={'Authorization':'Bearer '+token,'Origin':'http://different-host.invalid'})
    try:
        urllib.request.urlopen(request,timeout=3); raise AssertionError('Cross-origin mutation accepted')
    except urllib.error.HTTPError as error: check('cross-origin mutation protection preserved',error.code==400)
    with socket.socket() as closed:
        closed.settimeout(2)
        check('old listener closed',closed.connect_ex(('127.0.0.1',8080))!=0)
    check('configured host and token preserved', '127.0.0.1' in (data/'core-config.yml').read_text() and token in (data/'core-config.yml').read_text())
    command('module webui port','WebUI: http://127.0.0.1:'+str(newport))
    stop(); start('restart.log')
    check('restart loads saved port and serves dashboard',http(newport)[0]==200)
    check('startup log reports actual address',f'http://127.0.0.1:{newport}' in logpath.read_text(encoding='utf-8'))
finally:
    if server is not None and server.poll() is None: stop()
    (root/'results.json').write_text(json.dumps(checks,indent=2))
    print('Artifacts: '+str(root),flush=True)
