"""Read-only UI fixture server. Actual packaged HTML/CSS/JS; synthetic empty API data.
Not an integration server. Bind to loopback only; rejects all writes.
Run from repository root: python PlugModCore/src/test/theme-preview.py
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import json, mimetypes

ROOT = Path(__file__).resolve().parents[3]
MODULES = {'customcommands':'CustomCommands','placeholderapi':'PlaceholderAPI',
           'scoreboard':'ScoreBoard','pluginmanager':'PluginManager','template':'TemplateModule'}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):pass
    def do_GET(self):
        path = urlsplit(self.path).path
        data = None
        if path == '/api/modules':
            data = [{'internalId':k,'id':k,'name':'Commands & Permissions' if k=='customcommands' else v,
                     'state':'ENABLED' if k!='template' else 'UPLOADED','website':True,'dependencies':[]} for k,v in MODULES.items()]
        elif path.startswith('/api/modules/'):
            tail=path.rsplit('/',1)[-1]
            data={'session':{'capability':'preview-only'},
                  'management':{'state':{'revision':1,'groups':{},'players':{},'commands':{},'audit':[], 'defaultGroup':''},'players':[]},
                  'commands':{'commands':[],'statistics':{},'registered':[],'today':{},'events':[], 'integrations':{'runtime':'Preview fixture','permissions':'Preview fixture','placeholders':False,'placeholderDetails':''}},
                  'inventory':{'managed':[],'runtime':[],'data':[]},
                  'get':{'title':'Preview','lines':[]},
                  'list':{'items':[],'expansions':[],'total':0}}
            data=data.get(tail,[])
            if '/pluginmanager/' in path and tail=='inventory':data={'job':'inventory'}
            if '/pluginmanager/' in path and tail=='jobs':data={'status':'done','result':{'managed':[],'runtime':[],'data':[]}} 
        if data is not None:
            body=json.dumps(data).encode();ct='application/json'
        else:
            if path in ('/','/dashboard'): rel='PlugModCore/src/main/resources/web/dashboard.html'
            elif path == '/modules':rel='PlugModCore/src/main/resources/web/modules.html'
            elif path.startswith('/web/'):rel='PlugModCore/src/main/resources/web/'+path[5:]
            elif path.startswith('/modules/website/'):
                pieces=path.split('/'); module=MODULES.get(pieces[3]);
                if not module:self.send_error(404);return
                rel='Modules/'+module+'/src/main/resources/web/'+('/'.join(pieces[4:]) or 'index.html')
            else:self.send_error(404);return
            file=(ROOT/rel).resolve()
            if not file.is_relative_to(ROOT) or not file.is_file():self.send_error(404);return
            body=file.read_bytes()
            if path=='/modules':body=body.replace(b'{{MODULE_LIST}}',b'<ul><li data-id="customcommands">Commands &amp; Permissions</li></ul>')
            ct=mimetypes.guess_type(file)[0] or 'application/octet-stream'
        self.send_response(200);self.send_header('Content-Type',ct);self.send_header('Content-Length',len(body));self.end_headers();self.wfile.write(body)
    def do_POST(self):self.send_error(405,'Preview fixture is read-only')
HTTPServer(('127.0.0.1',18084),Handler).serve_forever()
