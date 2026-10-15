const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../../../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
test('every product entry loads the shared theme before feature CSS',()=>{
 const pages=['PlugModCore/src/main/resources/web/dashboard.html','PlugModCore/src/main/resources/web/index.html','PlugModCore/src/main/resources/web/modules.html','PlugModCore-Velocity/src/main/resources/web/network.html',...['CustomCommands','PlaceholderAPI','CommandPanels','ScoreBoard','TemplateModule'].map(m=>`Modules/${m}/src/main/resources/web/index.html`)];
 for(const page of pages){const html=read(page);assert.equal((html.match(/href="\/web\/design-system.css"/g)||[]).length,1,page);const links=[...html.matchAll(/<link[^>]+rel="stylesheet"[^>]*>/g)];assert.match(links[0][0],/design-system.css/,page);assert.match(html,/name="viewport"/,page);}
});
test('product rename preserves module and storage identities',()=>{
 const info=read('Modules/CustomCommands/src/main/resources/module.info.yml');assert.match(info,/^id: customcommands$/m);assert.match(info,/^name: Commands & Permissions$/m);assert.match(info,/^  title: Commands & Permissions$/m);assert.match(info,/main: org.wannes.customcommands.CustomCommandsModule/);
 const pom=read('Modules/CustomCommands/pom.xml');assert.match(pom,/<artifactId>CustomCommands<\/artifactId>/);assert.match(pom,/<finalName>CustomCommands<\/finalName>/);
 const html=read('Modules/CustomCommands/src/main/resources/web/index.html');assert.match(html,/<title>Commands &amp; Permissions<\/title>/);assert.match(html,/<h1>Commands &amp; Permissions<\/h1>/);
 const java=read('Modules/CustomCommands/src/main/java/org/wannes/customcommands/CommandStore.java');assert.match(java,/commands\.json/);assert.match(java,/management\.json/);
});
test('theme retains reference colors and adds accessible focus and reduced motion',()=>{
 const css=read('PlugModCore/src/main/resources/web/design-system.css');for(const token of ['--bg:#151922','--panel:#202630','--accent:#40c7c0','--text:#eef1f5'])assert.ok(css.includes(token));assert.match(css,/:focus-visible/);assert.match(css,/prefers-reduced-motion/);assert.match(css,/input::placeholder/);
 const channel=c=>{c/=255;return c<=.04045?c/12.92:((c+.055)/1.055)**2.4};const lum=h=>{const c=h.match(/../g).map(x=>channel(parseInt(x,16)));return c[0]*.2126+c[1]*.7152+c[2]*.0722};
 for(const text of ['eef1f5','aebbd0','80e7dc','fda4af'])for(const bg of ['151922','202630','202f46'])assert.ok((lum(text)+.05)/(lum(bg)+.05)>=4.5,`${text} on ${bg}`);
});
