/* Dashboard client-side script
   Extracted from dashboard.html for maintainability.
*/
(function(){
  /* ===== State ===== */
  let modules = [];
  let currentModuleIndex = null;

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

  function openModule(index){ currentModuleIndex = index; const hv = document.getElementById('homeView'); const mv = document.getElementById('moduleView'); if(hv) hv.classList.add('hidden'); if(mv) mv.classList.remove('hidden'); showGreeting(false); setModuleContentActive(true); renderModuleTopbar(); loadModuleWebsite(); }
  function goHome(){ const hv = document.getElementById('homeView'); const mv = document.getElementById('moduleView'); if(mv) mv.classList.add('hidden'); if(hv) hv.classList.remove('hidden'); showGreeting(true); setModuleContentActive(false); currentModuleIndex = null; }

  document.getElementById && document.getElementById('navHome') && (document.getElementById('navHome').onclick = goHome);

  /* ===== API helpers ===== */
  async function fetchModules(){
    try{
      // preserve currently selected module id so detail view stays in sync
      const selectedId = (currentModuleIndex != null && modules[currentModuleIndex]) ? modules[currentModuleIndex].internalId : null;

      const res = await fetch('/api/modules',{cache:'no-store'});
      if(!res.ok) throw new Error('Kon modules niet laden');
      const list = await res.json();
      modules = list.map(m=>({ internalId: m.internalId||m.id||m.fileName, name: m.name||m.fileName||'Unnamed', enabled: m.state==='ENABLED', loaded: m.state!=='UPLOADED', state: m.state, description: m.description||'', websiteEnabled: m.websiteEnabled, websiteEntry: m.websiteEntry }));

      // restore selected index if possible
      if (selectedId) {
        const idx = modules.findIndex(x => x.internalId === selectedId);
        currentModuleIndex = (idx >= 0) ? idx : null;
      }

      renderModules();

      // if we're in a detail view, update the topbar so buttons reflect live state
      if (currentModuleIndex != null) {
        renderModuleTopbar();
      }
    }catch(e){ console.error(e); const c = document.getElementById('modules'); if(c) c.innerHTML = '<div class="muted">Fout bij laden modules</div>'; }
  }

  async function waitForModuleState(id, expectedState, timeoutMs=7000, intervalMs=300){
    const start = Date.now();
    while(Date.now()-start < timeoutMs){
      try{ const res = await fetch('/api/modules',{cache:'no-store'}); if(res.ok){ const list = await res.json(); const m = list.find(x=>x.internalId===id); if(expectedState===null){ if(!m) return true; } else { if(m && m.state===expectedState) return true; } } }catch(e){}
      await new Promise(r=>setTimeout(r, intervalMs));
    }
    return false;
  }

  async function postAction(path, expectedState, moduleId){
    try{
      const res = await fetch(path, {method:'POST'});
      if(!res.ok){ const txt = await res.text(); throw new Error(txt||'Actie mislukt'); }

      if(moduleId && typeof expectedState === 'string'){
        const ok = await waitForModuleState(moduleId, expectedState);
        if(!ok){ await fetchModules(); showToast('info','Actie voltooid maar status-update vertraagd.'); return; }
        await fetchModules(); return;
      }

      if(moduleId && expectedState === null){ await waitForModuleState(moduleId, null); await fetchModules(); return; }

      await fetchModules();
    }catch(err){ showToast('error','Actie mislukt: '+err.message); }
  }

  /* ===== Rendering ===== */
  function renderModules(){
    const container = document.getElementById('modules');
    const list = document.getElementById('moduleList');
    if(!container||!list) return; container.innerHTML=''; list.innerHTML='';

    modules.forEach((m,i)=>{
      const card = document.createElement('div'); card.className='module'; card.onclick = ()=>openModule(i);
      card.innerHTML = `<div><h3>${escapeHtml(m.name)}</h3><div class="status">Status: ${m.enabled? 'Enabled':'Disabled'} | ${m.loaded? 'Loaded':'Unloaded'}</div></div>`;

      const btnEnable = document.createElement('button'); btnEnable.className='btn green'; btnEnable.innerText = m.enabled? 'Disable':'Enable'; btnEnable.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.enabled?'disable/':'enable/')+encodeURIComponent(m.internalId), (m.enabled?'DISABLED':'ENABLED'), m.internalId); };
      btnEnable.disabled = !m.loaded;

      const btnLoad = document.createElement('button'); btnLoad.className='btn gray'; btnLoad.innerText = m.loaded? 'Unload':'Load'; btnLoad.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.loaded?'unload/':'load/')+encodeURIComponent(m.internalId), (m.loaded?'UPLOADED':'DISABLED'), m.internalId); };
      btnLoad.disabled = !!(m.loaded && m.enabled);

      const btnDelete = document.createElement('button'); btnDelete.className='btn red'; btnDelete.innerText='Delete'; btnDelete.onclick = e=>{ e.stopPropagation(); showConfirm('Verwijder module '+m.name+'?').then(ok=>{ if(ok) postAction('/modules/delete/'+encodeURIComponent(m.internalId), null, m.internalId); }); };
      btnDelete.disabled = !!(m.loaded || m.enabled);

      if(btnEnable.disabled) btnEnable.style.opacity='.6'; if(btnLoad.disabled) btnLoad.style.opacity='.6'; if(btnDelete.disabled) btnDelete.style.opacity='.6';

      const wrap = document.createElement('div'); wrap.appendChild(btnEnable); wrap.appendChild(btnLoad); wrap.appendChild(btnDelete);
      card.appendChild(wrap);
      container.appendChild(card);

      const navBtn = document.createElement('button'); navBtn.innerText = m.name; navBtn.onclick = ()=>openModule(i); list.appendChild(navBtn);
    });
  }

  function renderModuleTopbar(){
    const m = modules[currentModuleIndex]; const top = document.getElementById('moduleTopbar'); if(!m||!top) return; top.innerHTML='';
    const btnEnable = document.createElement('button'); btnEnable.className='btn green'; btnEnable.innerText = m.enabled? 'Disable':'Enable'; btnEnable.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.enabled?'disable/':'enable/')+encodeURIComponent(m.internalId), (m.enabled?'DISABLED':'ENABLED'), m.internalId); };
    btnEnable.disabled = !m.loaded;

    const btnLoad = document.createElement('button'); btnLoad.className='btn gray'; btnLoad.innerText = m.loaded? 'Unload':'Load'; btnLoad.onclick = e=>{ e.stopPropagation(); postAction('/modules/'+(m.loaded?'unload/':'load/')+encodeURIComponent(m.internalId), (m.loaded?'UPLOADED':'DISABLED'), m.internalId); };
    btnLoad.disabled = !!(m.loaded && m.enabled);

    const btnDelete = document.createElement('button'); btnDelete.className='btn red'; btnDelete.innerText='Delete'; btnDelete.onclick = e=>{ e.stopPropagation(); showConfirm('Verwijder module '+m.name+'?').then(ok=>{ if(ok) postAction('/modules/delete/'+encodeURIComponent(m.internalId), null, m.internalId); }); };
    btnDelete.disabled = !!(m.loaded || m.enabled);

    if(btnEnable.disabled) btnEnable.style.opacity='.6'; if(btnLoad.disabled) btnLoad.style.opacity='.6'; if(btnDelete.disabled) btnDelete.style.opacity='.6';

    top.appendChild(btnEnable); top.appendChild(btnLoad); top.appendChild(btnDelete);
    // status text (placed after buttons)
    const statusDiv = document.createElement('div'); statusDiv.className = 'status'; statusDiv.style.marginLeft = '12px'; statusDiv.innerText = 'Status: ' + (m.enabled? 'Enabled':'Disabled') + ' | ' + (m.loaded? 'Loaded':'Unloaded');
    top.appendChild(statusDiv);
  }

  /* ===== Module website loader ===== */
  function loadModuleWebsite(){ const m = modules[currentModuleIndex]; const frame = document.getElementById('moduleFrame'); const content = document.getElementById('moduleContent'); if(!m||!content||!frame) return;
    const entry = (m.websiteEntry && m.websiteEntry.trim().length > 0) ? m.websiteEntry.trim() : 'web/index.html';
    frame.src = '/modules/website/'+encodeURIComponent(m.internalId)+'/?entry='+encodeURIComponent(entry);
    // ensure iframe is visible (in case previous code replaced innerHTML)
    content.classList.remove('muted');
  }

  /* ===== Upload ===== */
  async function uploadFiles(){
    const input = document.getElementById('fileInput'); const btn = document.getElementById('uploadBtn'); if(!input||!input.files.length){ showToast('info','Kies eerst een bestand'); return; }
    const files = Array.from(input.files); btn.disabled = true; btn.innerText = 'Uploading...';
    try{
      const fd = new FormData(); files.forEach(f=>fd.append('file', f, f.name));
      const res = await fetch('/modules/upload', { method: 'POST', body: fd });
      if(!res.ok){ const txt = await res.text(); throw new Error(txt || 'Upload mislukt'); }
      try{ const info = await res.json(); if(info && info.id) showToast('success','Upload voltooid (id: '+info.id+')'); else showToast('success','Upload voltooid'); }catch(e){ showToast('success','Upload voltooid'); }
      input.value = ''; await fetchModules(); goHome();
    }catch(err){ alert('Upload mislukt: '+err.message); }
    finally{ btn.disabled = false; btn.innerText = 'Upload'; }
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
      const cleanup = () => { modal.classList.add('hidden'); ok.removeEventListener('click', onOk); cancel.removeEventListener('click', onCancel); };
      const onOk = () => { cleanup(); resolve(true); };
      const onCancel = () => { cleanup(); resolve(false); };
      ok.addEventListener('click', onOk);
      cancel.addEventListener('click', onCancel);
    });
  }
  function escapeHtml(s){ return String(s).replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]); }

  // expose for debugging if needed
  window.pm_fetchModules = fetchModules;
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
