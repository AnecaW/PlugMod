// Time-sensitive message
function updateGreeting() {
  const h = new Date().getHours();
  let msg = "Good evening";
  if (h < 12) msg = "Good morning";
  else if (h < 18) msg = "Good afternoon";
  document.getElementById("timeMessage").innerText = msg;
}
updateGreeting();

// Mock module state (koppelt makkelijk aan je API)
let currentModule = null;

// Render modules
async function loadModules() {
  const res = await fetch("/api/modules");
  const modules = await res.json();

  const grid = document.getElementById("modulesGrid");
  const nav = document.getElementById("moduleNav");

  grid.innerHTML = "";
  nav.innerHTML = "<li data-home>Home</li>";

  modules.forEach(m => {
    // Card
    const card = document.createElement("div");
    card.className = "card";
    card.innerText = m.name;
    card.onclick = () => openModule(m);
    grid.appendChild(card);

    // Sidebar
    const li = document.createElement("li");
    li.innerText = m.name;
    li.onclick = () => openModule(m);
    nav.appendChild(li);
  });
}

function openModule(m) {
  currentModule = m;

  document.getElementById("sidebar").classList.remove("hidden");
  document.getElementById("homeView").style.display = "none";
  document.getElementById("moduleView").classList.remove("hidden");

  updateRibbon(m);

  // Load module HTML
  fetch(`/modules/website/${m.internalId}/${m.websiteEntry}`)
    .then(r => r.ok ? r.text() : "Geen HTML")
    .then(html => {
      document.getElementById("moduleContent").innerHTML = html;
    });
}

function updateRibbon(m) {
  setBtn("btnLoad", m.state === "UPLOADED");
  setBtn("btnEnable", m.state === "DISABLED");
  setBtn("btnDisable", m.state === "ENABLED");
  setBtn("btnUnload", m.state !== "UPLOADED");
  setBtn("btnDelete", m.state !== "ENABLED");
}

function setBtn(id, enabled) {
  document.getElementById(id).disabled = !enabled;
}

// Init
loadModules();
