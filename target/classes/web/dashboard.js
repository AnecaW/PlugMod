/* Dashboard client-side script
   Extracted from dashboard.html for maintainability.
*/
(function(){
  if(window.pmDashboardBound) return; window.pmDashboardBound = true;
  const transport=window.pmDashboardTransport;const request=(path,options)=>transport?transport.fetch(path,options):fetch(path,options);
  /* ===== State ===== */
  let modules = [];
  let currentModuleIndex = null;
  const pendingActions = new Set();
  let fetchRevision = 0;

  /* ===== Greeting ===== */
  function getGreeting(){
    const h = new Date().getHours();
    if(h<12) return 'Good morning';
    if(h<18) return 'Good afternoon';
    return 'Good evening';
  }
  function updateGreeting(){ const g = document.getElementById('greeting'); if(g) g.innerText = getGreeting(); }
  updateGreeting(); setInterval(updateGreeting, 60000);

  /* ===== Navigation helpers ===== */
  function showGreeting(show){ const el = document.getElementById('greeting'); if(el) el.style.display = show ? 'block' : 'none'; }

  // toggle content area into module-view layout so iframe can fill remaining space
  function setModuleContentActive(active){ const content = document.querySelector('.content'); if(!content) return; if(active) content.classList.add('module-active'); else content.classList.remove('module-active'); }

  function updateNavigation(){ const home=document.getElementById("navHome"); if(home) home.classList[currentModuleIndex===null?"add":"remove"]("active"); const list=document.getElementById("moduleList"); if(list) for(const node of list.children) { const active=node.dataset.moduleId===modules[currentModuleIndex]?.internalId; node.classList[active?"add":"remove"]("active"); if(active) node.setAttribute?.("aria-current","page"); else node.removeAttribute?.("aria-current"); } }
  function openModule(id){ currentModuleIndex = modules.findIndex(m=>m.internalId === id); if(currentModuleIndex<0) return; const hv = document.getElementById('homeView'); const mv = document.getElementById('moduleView'); if(hv) hv.classList.add('hidden'); if(mv) mv.classList.remove('hidden'); showGreeting(false); setModuleContentActive(true); renderModuleTopbar(); loadModuleWebsite(); updateNavigation(); }
  function goHome(){ const hv = document.getElementById('homeView'); const mv = document.getElementById('moduleView'); if(mv) mv.classList.add('hidden'); if(hv) hv.classList.remove('hidden'); showGreeting(true); setModuleContentActive(false); currentModuleIndex = null; updateNavigation(); }

  document.getElementById && document.getElementById('navHome') && (document.getElementById('navHome').onclick = goHome);

  /* ===== API helpers ===== */
  async function fetchModules(){
    if(transport&&!transport.active())return;
    const revision = ++fetchRevision;
    try{
      // preserve currently selected module id so detail view stays in sync
      const res = await request('/api/modules',{cache:'no-store'});
      if(!res.ok) throw new Error('Kon modules niet laden');
      const list = await res.json();
      if(revision !== fetchRevision) return;
      const selectedId = (currentModuleIndex != null && modules[currentModuleIndex]) ? modules[currentModuleIndex].internalId : null;
      modules = list.filter(m=>typeof m.internalId === 'string' && m.internalId).map(m=>({ internalId: m.internalId, name: m.name||m.fileName||'Unnamed', enabled: m.state==='ENABLED', loaded: !!m.loaded, state: m.state, error: m.error || '', description: m.description||'', websiteEnabled: m.websiteEnabled, websiteEntry: m.websiteEntry }));

      // restore selected index if possible
      if (selectedId) {
        const idx = modules.findIndex(x => x.internalId === selectedId);
        currentModuleIndex = (idx >= 0) ? idx : null;
      }

      renderModules(); updateNavigation();

      // if we're in a detail view, update the topbar so buttons reflect live state
      if (currentModuleIndex != null) {
        renderModuleTopbar();
      }
    }catch(e){ if(transport&&e.name==='AbortError')return;console.error(e); const c = document.getElementById('modules'); if(c) c.innerHTML = '<div class="muted">Fout bij laden modules</div>'; }
  }

  async function waitForModuleState(id, expectedState, timeoutMs=7000, intervalMs=300, contextToken=transport?.contextToken()){
    const start = Date.now();
    while(Date.now()-start < timeoutMs){
      if(transport&&!transport.isCurrent(contextToken))return false;
      try{ const res = await request('/api/modules',{cache:'no-store'}); if(res.ok){ const list = await res.json(); const m = list.find(x=>x.internalId===id); if(expectedState===null){ if(!m) return true; } else { if(m && m.state===expectedState) return true; } } }catch(e){}
      await new Promise(r=>setTimeout(r, intervalMs));
    }
    return false;
  }

  async function postAction(path, expectedState, moduleId){
    if(!moduleId || pendingActions.has(moduleId)) return;
    const contextToken=transport?.contextToken();
    const selected = modules.find(m=>m.internalId === moduleId);
    if(!selected) return;
    pendingActions.add(moduleId); renderModules(); if(currentModuleIndex != null) renderModuleTopbar();
    try{
      const res = await request(path, {method:'POST'});
      if(!res.ok){ const txt = await res.text(); throw new Error(txt||'Actie mislukt'); }
      if(transport&&!transport.isCurrent(contextToken))return;
      showToast('info', selected.name + ': ' + path.split('/')[2] + ' completed');

      if(moduleId && typeof expectedState === 'string'){
        const ok = await waitForModuleState(moduleId, expectedState,7000,300,contextToken);
        if(transport&&!transport.isCurrent(contextToken))return;
        if(!ok){ await fetchModules(); showToast('info','Actie voltooid maar status-update vertraagd.'); return; }
        await fetchModules(); return;
      }

      if(moduleId && expectedState === null){ await waitForModuleState(moduleId, null,7000,300,contextToken); await fetchModules(); return; }

      await fetchModules();
    }catch(err){ showToast('error',selected.name + ': '+err.message); }
    finally { if(transport&&!transport.isCurrent(contextToken))return;pendingActions.delete(moduleId); renderModules(); if(currentModuleIndex != null) renderModuleTopbar(); }
  }

  /* ===== Rendering ===== */
  function renderModules(){
    const container = document.getElementById('modules');
    const list = document.getElementById('moduleList');
    if(!container||!list) return; container.innerHTML=''; list.innerHTML='';

    modules.forEach((m,i)=>{
      const card = document.createElement('div'); card.className='module'; card.onclick = ()=>openModule(m.internalId);
      card.innerHTML = `<div><h3>${escapeHtml(m.name)}</h3><div class="status">Status: ${escapeHtml(m.state)} · ${m.loaded? 'Loaded':'Unloaded'}</div></div>`;

      const btnEnable = document.createElement('button'); btnEnable.className='btn green'; btnEnable.innerText = m.enabled? 'Disable':'Enable'; btnEnable.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.enabled?'disable/':'enable/')+encodeURIComponent(m.internalId), (m.enabled?'DISABLED':'ENABLED'), m.internalId); };
      btnEnable.disabled = !['ENABLED','DISABLED'].includes(m.state);

      const btnLoad = document.createElement('button'); btnLoad.className='btn gray'; btnLoad.innerText = m.loaded? 'Unload':'Load'; btnLoad.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.loaded?'unload/':'load/')+encodeURIComponent(m.internalId), (m.loaded?'UPLOADED':'DISABLED'), m.internalId); };
      btnLoad.disabled = !['UPLOADED','DISABLED','ENABLED','FAILED'].includes(m.state);

      const btnDelete = document.createElement('button'); btnDelete.className='btn red'; btnDelete.innerText='Delete'; btnDelete.onclick = e=>{ e.stopPropagation(); showConfirm('Verwijder module '+m.name+'?').then(ok=>{ if(ok) postAction('/modules/delete/'+encodeURIComponent(m.internalId), null, m.internalId); }); };
      btnDelete.disabled = !!(m.loaded || m.enabled);

      if(btnEnable.disabled) btnEnable.style.opacity='.6'; if(btnLoad.disabled) btnLoad.style.opacity='.6'; if(btnDelete.disabled) btnDelete.style.opacity='.6';

      const wrap = document.createElement('div'); wrap.appendChild(btnEnable); wrap.appendChild(btnLoad); wrap.appendChild(btnDelete);
      if(pendingActions.has(m.internalId)) for(const button of [btnEnable,btnLoad,btnDelete]) button.disabled = true;
      card.dataset.moduleId = m.internalId;
      card.appendChild(wrap);
      container.appendChild(card);

      const navBtn = document.createElement('button'); navBtn.innerText = m.name; navBtn.dataset.moduleId=m.internalId; navBtn.onclick = ()=>openModule(m.internalId); list.appendChild(navBtn);
    });
  }

  function renderModuleTopbar(){
    const m = modules[currentModuleIndex]; const top = document.getElementById('moduleTopbar'); if(!m||!top) return; top.innerHTML='';
    const btnEnable = document.createElement('button'); btnEnable.className='btn green'; btnEnable.innerText = m.enabled? 'Disable':'Enable'; btnEnable.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.enabled?'disable/':'enable/')+encodeURIComponent(m.internalId), (m.enabled?'DISABLED':'ENABLED'), m.internalId); };
    btnEnable.disabled = !['ENABLED','DISABLED'].includes(m.state);

    const btnLoad = document.createElement('button'); btnLoad.className='btn gray'; btnLoad.innerText = m.loaded? 'Unload':'Load'; btnLoad.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.loaded?'unload/':'load/')+encodeURIComponent(m.internalId), (m.loaded?'UPLOADED':'DISABLED'), m.internalId); };
    btnLoad.disabled = !['UPLOADED','DISABLED','ENABLED','FAILED'].includes(m.state);

    const btnDelete = document.createElement('button'); btnDelete.className='btn red'; btnDelete.innerText='Delete'; btnDelete.onclick = e=>{ e.stopPropagation(); showConfirm('Verwijder module '+m.name+'?').then(ok=>{ if(ok) postAction('/modules/delete/'+encodeURIComponent(m.internalId), null, m.internalId); }); };
    btnDelete.disabled = !!(m.loaded || m.enabled);

    if(btnEnable.disabled) btnEnable.style.opacity='.6'; if(btnLoad.disabled) btnLoad.style.opacity='.6'; if(btnDelete.disabled) btnDelete.style.opacity='.6';

    top.appendChild(btnEnable); top.appendChild(btnLoad); top.appendChild(btnDelete);
    const btnReload = document.createElement('button'); btnReload.className='btn gray'; btnReload.innerText='Reload'; btnReload.disabled = !['ENABLED','DISABLED'].includes(m.state); btnReload.onclick = ()=>postAction('/modules/reload/'+encodeURIComponent(m.internalId), m.state, m.internalId); top.appendChild(btnReload);
    // status text (placed after buttons)
    if(pendingActions.has(m.internalId)) for(const button of [btnEnable,btnLoad,btnDelete,btnReload]) button.disabled = true;
    const statusDiv = document.createElement('div'); statusDiv.className = 'status'; statusDiv.style.marginLeft = '12px'; statusDiv.innerText = 'Status: ' + m.state + (m.loaded? ' Loaded':' Unloaded');
    top.appendChild(statusDiv);
  }

  /* ===== Module website loader ===== */
  function loadModuleWebsite(){ const m = modules[currentModuleIndex]; const frame = document.getElementById('moduleFrame'); const content = document.getElementById('moduleContent'); if(!m||!content||!frame) return;
    const entry = (m.websiteEntry && m.websiteEntry.trim().length > 0) ? m.websiteEntry.trim() : 'web/index.html';
    frame.src = transport?transport.moduleUrl(m.internalId,entry):'/modules/website/'+encodeURIComponent(m.internalId)+'/?entry='+encodeURIComponent(entry);
    // ensure iframe is visible (in case previous code replaced innerHTML)
    content.classList.remove('muted');
  }

  /* ===== Upload ===== */
  async function uploadFiles(){
    const input = document.getElementById('fileInput'); const btn = document.getElementById('uploadBtn'); if(!input||!input.files.length){ showToast('info','Kies eerst een bestand'); return; }
    const contextToken=transport?.contextToken();
    const files = Array.from(input.files); btn.disabled = true; btn.innerText = 'Module wordt geüpload...';window.pmUploadProgress=message=>{if(!transport||transport.isCurrent(contextToken))btn.innerText=message;};
    try{
      for(const file of files){if(transport&&!transport.isCurrent(contextToken))return;const preview=new FormData();preview.append('file',file,file.name);const checked=await request('/modules/validate',{method:'POST',body:preview});if(!checked.ok)throw new Error(await checked.text());const manifest=await checked.json();if(transport&&!transport.isCurrent(contextToken))return;if(!confirm((manifest.existing?'Bestaande module bijwerken op deze backend? Ontlaad deze eerst.':transport?'Install on this selected runtime?':'Install on this Paper server?')+'\n'+JSON.stringify(manifest,null,2)))return;}
      if(transport&&!transport.isCurrent(contextToken))return;
      const fd = new FormData(); files.forEach(f=>fd.append('file', f, f.name));
      const res = await request('/modules/upload', { method: 'POST', body: fd });
      if(!res.ok){ const txt = await res.text(); throw new Error(txt || 'Upload mislukt'); }
      try{ const info = await res.json();if(info?.results){for(const result of info.results)showToast(result.loaded===false?'error':'success',result.id+': '+(result.loaded===false?(result.restoredPrevious?'Bijwerken mislukt; vorige module behouden: ':'Module geïnstalleerd, maar laden mislukt: ')+result.error:result.loaded?'Module geïnstalleerd en geladen.':'Module geïnstalleerd.'));if(info.results.some(result=>result.loaded===false)){await fetchModules();return;}} else  if(info && info.id) showToast('success','Upload voltooid (id: '+info.id+')'); else showToast('success','Upload voltooid'); }catch(e){ showToast('success','Upload voltooid'); }
      if(transport&&!transport.isCurrent(contextToken))return;
      input.value = ''; await fetchModules(); goHome();
    }catch(err){ alert('Upload mislukt: '+err.message); }
    finally{ window.pmUploadProgress=null;btn.disabled = false; btn.innerText = 'Module uploaden'; }
  }

  document.addEventListener('DOMContentLoaded', ()=>{ document.getElementById && document.getElementById('uploadBtn') && document.getElementById('uploadBtn').addEventListener('click', uploadFiles); fetchModules();
    // poll modules every 3s to keep UI (including detail topbar) up-to-date
    setInterval(fetchModules, 3000);
  });

  /* ===== Utilities ===== */
  /* ===== Confirmation modal helper ===== */
  function showConfirm(message){
    return new Promise(resolve=>{
      const modal = document.getElementById('confirmModal');
      const msg = document.getElementById('confirmMessage');
      const ok = document.getElementById('confirmOk');
      const cancel = document.getElementById('confirmCancel');
      if(!modal||!msg||!ok||!cancel) return resolve(window.confirm(message));
      msg.innerText = message;
      modal.classList.remove('hidden');
      const previousFocus = document.activeElement;
      cancel.focus?.();
      const onKey = event => { if(event.key==='Escape'){event.preventDefault();onCancel();} else if(event.key==='Tab'){event.preventDefault();(document.activeElement===cancel?ok:cancel).focus?.();} };
      modal.addEventListener('keydown', onKey);
      const cleanup = () => { modal.classList.add('hidden'); modal.removeEventListener('keydown', onKey); previousFocus?.focus?.(); ok.removeEventListener('click', onOk); cancel.removeEventListener('click', onCancel); };
      const onOk = () => { cleanup(); resolve(true); };
      const onCancel = () => { cleanup(); resolve(false); };
      ok.addEventListener('click', onOk);
      cancel.addEventListener('click', onCancel);
    });
  }
  function escapeHtml(s){ return String(s).replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]); }

  // expose for debugging if needed
  window.pm_fetchModules = fetchModules;
  window.pmDashboardContextChanged = function(){fetchRevision++;modules=[];currentModuleIndex=null;pendingActions.clear();const frame=document.getElementById('moduleFrame');if(frame)frame.src='about:blank';goHome();renderModules();fetchModules();};
})();

/* ===== Toast helper (global) ===== */
function showToast(type, message, timeout = 4500) {
  const container = document.getElementById('toast-container');
  if (!container) return alert(message);
  const t = document.createElement('div');
  t.className = 'toast ' + (type || 'info');
  t.innerText = message;
  container.appendChild(t);
  // allow CSS transition for hide
  setTimeout(() => { t.classList.add('hide'); }, timeout - 200);
  setTimeout(() => { try { container.removeChild(t); } catch (e) {} }, timeout);
}



