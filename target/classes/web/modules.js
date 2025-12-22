(function(){
  const api = '/api/modules';

  function el(tag, props, ...children){
    const e = document.createElement(tag);
    if(props){ Object.entries(props).forEach(([k,v])=>{ if(k.startsWith('on') && typeof v==='function') e.addEventListener(k.slice(2).toLowerCase(), v); else if(k==='html') e.innerHTML=v; else e.setAttribute(k,v); }) }
    children.flat().forEach(c=>{ if(typeof c==='string') e.appendChild(document.createTextNode(c)); else if(c) e.appendChild(c)});
    return e;
  }

  async function fetchJson(){
    const res = await fetch(api, {cache:'no-store'});
    if(!res.ok) throw new Error('Kon modules niet laden');
    return res.json();
  }

  async function refreshModules(){
    try{
      const modules = await fetchJson();
      renderGrid(modules);
    }catch(e){
      console.error(e);
      document.getElementById('modulesGrid').innerHTML = '<div style="color:#900">Fout bij laden modules</div>';
    }
  }

  function renderGrid(modules){
    const grid = document.getElementById('modulesGrid');
    grid.innerHTML='';
    modules.forEach(m => {
      const card = el('article', {class:'card', onclick:()=>openDetail(m)});
      const title = el('h3', null, m.name || m.fileName || 'Unknown');
      const meta = el('div', {class:'meta'}, (m.internalId || '') + ' • ' + (m.state||''));
      const actions = el('div', {class:'actions'});

      // (enable toggle removed - actions handled via buttons)

      // action buttons
      if (m.state === 'UPLOADED') {
        actions.appendChild(actionButton('Load', ()=>postAction('/modules/load/'+m.internalId)));
        actions.appendChild(actionButton('Delete', ()=>postAction('/modules/delete/'+m.internalId)));
      }
      if (m.state === 'DISABLED') {
        actions.appendChild(actionButton('Enable', ()=>postAction('/modules/enable/'+m.internalId)));
        actions.appendChild(actionButton('Unload', ()=>postAction('/modules/unload/'+m.internalId)));
      }
      if (m.state === 'ENABLED') {
        actions.appendChild(actionButton('Disable', ()=>postAction('/modules/disable/'+m.internalId)));
      }
      if (m.state === 'FAILED') {
        actions.appendChild(actionButton('Unload', ()=>postAction('/modules/unload/'+m.internalId)));
        actions.appendChild(actionButton('Delete', ()=>postAction('/modules/delete/'+m.internalId)));
      }

      card.appendChild(title);
      card.appendChild(meta);
      card.appendChild(actions);
      grid.appendChild(card);
    });
  }

  function actionButton(label, fn){
    const b = el('button', {type:'button'} , label);
    b.addEventListener('click', e=>{ e.stopPropagation(); fn(); });
    return b;
  }

  async function postAction(path){
    try{
      const res = await fetch(path, {method:'POST'});
      if(!res.ok) throw new Error('Actie mislukt');
      await refreshModules();
    }catch(e){
      alert('Actie mislukt: '+e.message);
    }
  }

  async function onToggle(m, enabled){
    // removed - kept for compatibility if needed later
    return Promise.resolve();
  }

  function openDetail(m){
    history.pushState({module:m.internalId}, '', '#module-'+m.internalId);
    const pane = document.getElementById('detailContent');
    if (!pane) {
      if (window.openModule && typeof window.openModule === 'function') {
        try { window.openModule(m); } catch(e) { /* ignore */ }
      }
      return;
    }

    pane.innerHTML='';
    const title = el('h2', null, m.name || m.fileName);
    const id = el('div', {class:'meta'}, 'ID: '+m.internalId);
    const state = el('div', {class:'meta'}, 'State: '+m.state);
    const desc = el('p', null, m.description || 'Geen omschrijving');
    pane.appendChild(title);
    pane.appendChild(id);
    pane.appendChild(state);
    pane.appendChild(desc);

    if (m.websiteEnabled && m.websiteEntry) {
      // try to fetch the module specific HTML (graceful fallback)
      const entryPath = '/modules/website/' + encodeURIComponent(m.internalId) + '/' + encodeURIComponent(m.websiteEntry);
      fetch(entryPath).then(r=>{
        if(r.ok) return r.text();
        throw new Error('Geen module-website');
      }).then(html=>{
        const wrap = el('div', {html: html});
        pane.appendChild(el('h4', null, 'Module website'));
        pane.appendChild(wrap);
      }).catch(()=>{
        pane.appendChild(el('div', {style:'color:#666'}, 'Geen module-specifieke site beschikbaar via '+entryPath));
      });
    }
  }

  // navigation
  const navItems = document.querySelectorAll('[data-nav]');
  if (navItems) {
    navItems.forEach(a=>a.addEventListener('click', e=>{
      e.preventDefault();
      const v = a.getAttribute('data-nav');
      showView(v);
    }));
  }

  function showView(name){
    const homeEl = document.getElementById('homeView');
    if(homeEl) homeEl.style.display = name==='home' ? '' : 'none';
    const modulesEl = document.getElementById('modulesView');
    if(modulesEl) modulesEl.style.display = name==='modules' ? '' : 'none';
    if(name==='modules') refreshModules();
  }

  // upload handling
  document.getElementById('uploadForm').addEventListener('submit', async function(e){
    e.preventDefault();
    const fileInput = document.getElementById('uploadFile');
    if(!fileInput.files.length) return;
    const fd = new FormData();
    fd.append('file', fileInput.files[0], fileInput.files[0].name);
    try{
      const res = await fetch('/modules/upload', {method:'POST', body: fd});
      if(!res.ok) {
        const txt = await res.text();
        throw new Error(txt || 'Upload mislukt');
      }
      // try parse json response
      let info = null;
      try { info = await res.json(); } catch(e) { /* ignore */ }
      fileInput.value='';
      alert('Upload voltooid' + (info && info.id ? ' (id: '+info.id+')' : ''));
      refreshModules();
      showView('modules');
    }catch(err){
      alert('Upload mislukt: '+err.message);
    }
  });

  // handle back/forward
  window.addEventListener('popstate', ev=>{
    const s = location.hash;
    if(!s) return;
    if(s.startsWith('#module-')){
      const id = s.replace('#module-','');
      fetch(api).then(r=>r.json()).then(list=>{ const m = list.find(x=>x.internalId===id); if(m) openDetail(m); });
    }
  });

  // initial
  document.addEventListener('DOMContentLoaded', ()=>{
    showView('home');
  });
})();
