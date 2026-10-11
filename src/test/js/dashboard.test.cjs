const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/dashboard.js'), 'utf8');
const flush = async () => { for(let i=0;i<5;i++) await new Promise(setImmediate); };
class Node {
  constructor(){this.children=[];this.style={};this.dataset={};this.disabled=false;this.classList={add(){},remove(){}};}
  set innerHTML(value){this.html=value;this.children=[];} get innerHTML(){return this.html||'';}
  appendChild(node){this.children.push(node);} addEventListener(){} removeEventListener(){}
}
async function harness() {
  const nodes=Object.fromEntries(['modules','moduleList','moduleTopbar','homeView','moduleView','moduleContent','moduleFrame','greeting','navHome'].map(id=>[id,new Node()]));
  let rows=['scoreboard','placeholderapi','playerstats','announcements','tablist'].map(internalId=>({internalId,name:internalId,state:'UPLOADED',loaded:false}));
  const posts=[],ready=[],intervals=[],alerts=[];let hold=false, release;
  const sandbox={document:{getElementById:id=>nodes[id],querySelector:()=>new Node(),createElement:()=>new Node(),addEventListener:(event,fn)=>ready.push(fn)},window:{confirm:()=>true},console,Date,FormData,
    setInterval:fn=>intervals.push(fn),setTimeout(){},alert:message=>alerts.push(message),
    fetch:async(url,options={})=>{
      if(options.method==='POST'){
        posts.push(url);if(hold)await new Promise(resolve=>{release=resolve;});
        const [, ,action,encoded]=url.split('/');const id=decodeURIComponent(encoded);const row=rows.find(m=>m.internalId===id);
        assert.ok(row,'operation must identify a real row');
        if(action==='load'){row.loaded=true;row.state='DISABLED';if(id==='scoreboard'){const provider=rows.find(m=>m.internalId==='placeholderapi');provider.loaded=true;provider.state='DISABLED';}}
        if(action==='unload'){row.loaded=false;row.state='UPLOADED';}
        return {ok:true,text:async()=>''};
      }
      return {ok:true,json:async()=>rows.map(row=>({...row}))};
    }};
  vm.createContext(sandbox);vm.runInContext(source,sandbox);ready[0]();await flush();
  return {nodes,posts,ready,intervals,alerts,
    card:id=>nodes.modules.children.find(n=>n.dataset.moduleId===id),
    click(id,index=1){this.card(id).children[0].children[index].onclick({stopPropagation(){}});},
    async refresh(){await sandbox.window.pm_fetchModules();await flush();},
    reorder(){rows.reverse();},enabled(){rows.forEach(row=>{row.state='ENABLED';row.loaded=true;});},
    hold(){hold=true;},async release(){hold=false;release();await flush();},duplicateScript(){vm.runInContext(source,sandbox);}
  };
}
test('load targets one ID plus its declared provider, then independent rows load separately',async()=>{
  const h=await harness();h.click('scoreboard');await flush();
  assert.deepEqual(h.posts,['/modules/load/scoreboard']);
  assert.equal(h.card('announcements').children[0].children[1].innerText,'Load');
  h.click('tablist');await flush();h.click('announcements');await flush();
  assert.deepEqual(h.posts,['/modules/load/scoreboard','/modules/load/tablist','/modules/load/announcements']);
});
test('unload is available on enabled independent modules and targets only that module',async()=>{
  const h=await harness();h.enabled();await h.refresh();const button=h.card('announcements').children[0].children[1];
  assert.equal(button.disabled,false);h.click('announcements');await flush();
  assert.deepEqual(h.posts,['/modules/unload/announcements']);
  for(const id of ['scoreboard','placeholderapi','playerstats','tablist'])assert.equal(h.card(id).children[0].children[1].innerText,'Unload');
});
test('detail buttons preserve selected ID after reorder and row refresh',async()=>{
  const h=await harness();h.card('tablist').onclick();h.reorder();await h.refresh();
  h.nodes.moduleTopbar.children[1].onclick({stopPropagation(){}});await flush();
  assert.deepEqual(h.posts,['/modules/load/tablist']);
  h.click('announcements');await flush();assert.equal(h.posts[1],'/modules/load/announcements');
});
test('duplicate clicks, refreshed buttons and duplicate scripts cannot repeat an operation',async()=>{
  const h=await harness();h.hold();h.click('tablist');h.click('tablist');await h.refresh();
  assert.equal(h.card('tablist').children[0].children[1].disabled,true);
  assert.equal(h.card('announcements').children[0].children[1].disabled,false);
  h.click('tablist');h.duplicateScript();assert.equal(h.ready.length,1);assert.equal(h.posts.length,1);
  await h.release();assert.equal(h.card('tablist').children[0].children[1].disabled,false);
});
test('legacy module forms deduplicate submissions and surface selected-module failures',async()=>{
  const html=fs.readFileSync(path.join(__dirname,'../../main/resources/web/modules.html'),'utf8');
  const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
  const listeners=[],alerts=[],posts=[];let release;
  const button={disabled:false};const row={getAttribute:name=>name==='data-id'?'announcements':'ENABLED'};
  const form={classList:{contains:()=>true},getAttribute:()=>'/modules/unload/announcements',closest:()=>row,querySelectorAll:()=>[button]};
  const sandbox={window:{},document:{addEventListener:(name,fn)=>listeners.push(fn),querySelectorAll:()=>[form]},FormData:class{},Date,alert:s=>alerts.push(s),
    fetch:async url=>{posts.push(url);await new Promise(resolve=>{release=resolve;});return {ok:false,text:async()=> 'Stop/unload dependant scoreboard first'};}};
  vm.createContext(sandbox);vm.runInContext(script,sandbox);vm.runInContext(script,sandbox);assert.equal(listeners.length,1);
  const event={target:form,preventDefault(){}};const first=listeners[0](event);await listeners[0](event);
  assert.deepEqual(posts,['/modules/unload/announcements']);assert.equal(button.disabled,true);
  release();await first;assert.equal(button.disabled,false);assert.match(alerts[0],/dependant scoreboard/);
});
