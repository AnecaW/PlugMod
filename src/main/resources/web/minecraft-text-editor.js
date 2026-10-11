/* The original ScoreBoard text editor. One implementation shared by both module pages. */
window.MinecraftTextEditor=(()=>{
const RIGHT_MARKER='{right}';
const changes=new WeakMap();let defaults={change:()=>{},preview:()=>{}};
function scheduleAutoSave(){(changes.get(activeTextEditor)||defaults).change();}
function updatePreview(){(changes.get(activeTextEditor)||defaults).preview();}
function applyEditorOutline(editor){
    if(!editor) return;

    // helper: get computed color string from a node (element or text node's parent)
    const getComputedColorOfNode = (node)=>{
        try{
            if(!node) return null;
            const el = (node.nodeType === Node.TEXT_NODE) ? node.parentElement : node;
            if(!el) return null;
            return window.getComputedStyle(el).color || null;
        }catch(e){ return null; }
    };

    // Try selection first (if selection within this editor)
    let colorStr = null;
    try{
        const sel = window.getSelection();
        if(sel && sel.rangeCount){
            const anchor = sel.anchorNode;
            if(anchor && editor.contains(anchor)){
                colorStr = getComputedColorOfNode(anchor);
            }
        }
    }catch(e){ colorStr = null; }

    // fallback: look for any inline-colored element inside editor
    if(!colorStr){
        const colored = editor.querySelector('[style*="color:"]') || editor.querySelector('font[color]');
        if(colored) colorStr = window.getComputedStyle(colored).color;
    }

    // fallback: pick first text node with content and sample its parent
    if(!colorStr){
        const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT, null, false);
        let n;
        while((n = walker.nextNode())){
            if(n.nodeValue && n.nodeValue.trim().length){ colorStr = getComputedColorOfNode(n); break; }
        }
    }

    // final fallback: computed style of editor itself
    if(!colorStr) colorStr = window.getComputedStyle(editor).color;

    // parse colorStr into rgb
    const parseRgb = (s)=>{
        if(!s) return null;
        s = s.trim();
        // rgb/rgba
        let m = s.match(/rgba?\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
        if(m) return [parseInt(m[1],10), parseInt(m[2],10), parseInt(m[3],10)];
        // hex
        m = s.match(/^#([0-9a-f]{6})$/i);
        if(m) return [parseInt(m[1].substr(0,2),16), parseInt(m[1].substr(2,2),16), parseInt(m[1].substr(4,2),16)];
        return null;
    };

    const rgb = parseRgb(colorStr);
    editor.classList.remove('outline-white','outline-black');
    if(!rgb) return;
    // compute simple luminance
    const lum = (0.2126*rgb[0] + 0.7152*rgb[1] + 0.0722*rgb[2]) / 255;
    // choose outline: dark text -> white outline; light text -> black outline
    if(lum < 0.5) editor.classList.add('outline-white'); else editor.classList.add('outline-black');
}

// When the user types the very first character in an empty editor, make that text white by default
function attachDefaultWhiteBehavior(editor){
    if(!editor) return;
    // keydown handles normal typing; paste handled separately
    editor.addEventListener('keydown', function firstCharHandler(e){
        if(e.key === 'Enter') return;
        // only react to character keys
        if(e.key && e.key.length === 1){
            const before = (editor.textContent||'').trim();
            if(before.length === 0){
                // don't override if an inline style was already set
                if(!editor.style.color){
                    editor.style.color = '#ffffff';
                    applyEditorOutline(editor);
                }
            }
        }
    });

    // If user pastes into empty editor
    editor.addEventListener('paste', ()=>{
        const before = (editor.textContent||'').trim();
        if(before.length === 0 && !editor.style.color){
            editor.style.color = '#ffffff';
            applyEditorOutline(editor);
        }
    });
}

const mcColorMap = {
    "#000000":"§0","#0000aa":"§1","#00aa00":"§2","#00aaaa":"§3",
    "#aa0000":"§4","#aa00aa":"§5","#ffaa00":"§6","#aaaaaa":"§7",
    "#555555":"§8","#5555ff":"§9","#55ff55":"§a","#55ffff":"§b",
    "#ff5555":"§c","#ff55ff":"§d","#ffff55":"§e","#ffffff":"§f"
};

// reverse map from code -> hex
const codeToHex = {};
Object.keys(mcColorMap).forEach(hex=>{ codeToHex[mcColorMap[hex].slice(1)] = hex; });

const stripMcCodes = text => (text||"")
    .replace(/§x(§[0-9A-Fa-f]){6}/g, "")
    .replace(/§[0-9A-FK-ORa-fk-or]/g, "");

const rgbToHex = rgb => {
    const m = rgb.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/i);
    return m ? ("#" + (+m[1]).toString(16).padStart(2,'0') + (+m[2]).toString(16).padStart(2,'0') + (+m[3]).toString(16).padStart(2,'0')) : null;
};

// Parse a string containing MC codes into HTML (returns innerHTML string)
function mcToHtml(text){
    if(!text) return '';
    const out = [];
    // maintain current state
    let state = {color:null,bold:false,italic:false,underline:false,strike:false,magic:false};

    const flushChar = (ch)=>{
        if(ch === '') return;
        let style = '';
        if(state.color) style += `color:${state.color};`;
        if(state.bold) style += 'font-weight:700;';
        if(state.italic) style += 'font-style:italic;';
        if(state.underline || state.strike){
            const decos = [];
            if(state.underline) decos.push('underline');
            if(state.strike) decos.push('line-through');
            style += `text-decoration:${decos.join(' ')};`;
            style += 'text-decoration-thickness:2px;';
            style += 'text-underline-offset:2px;';
        }
        // For magic codes, the preview should show rapidly changing characters rather than blur.
        if(state.magic){
            out.push(`<span class="mc-magic" data-original="${escapeAttr(ch)}" style="${style}">${escapeHtml(ch)}</span>`);
            return;
        }
        if(style) out.push(`<span style="${style}">${escapeHtml(ch)}</span>`);
        else out.push(escapeHtml(ch));
    };

    for(let i=0;i<text.length;i++){
        const ch = text[i];
        if(text.startsWith('{right}',i)){out.push('<span data-right-marker="true" contenteditable="false" class="right-marker" title="Everything after this marker is right-aligned">⇥</span>');i+=6;continue;}
        if(ch === '§'){
            const code = (text[i+1]||'').toLowerCase();
            if(code === 'x'){
                // hex sequence: §x§R§R§G§G§B§B
                const seq = text.substr(i,14); // length 14: § x §R §R §G §G §B §B
                const m = seq.match(/§x(§[0-9A-Fa-f]){6}/);
                if(m){
                    // extract hex digits
                    let hex='';
                    for(let j=i+3;j<i+14;j+=2){ hex += text[j]; }
                    state.color = '#'+hex;
                    i += 13; // advance
                    continue;
                }
            }

            // single char codes
            switch(code){
                case '0': state.color = '#000000'; break;
                case '1': state.color = '#0000aa'; break;
                case '2': state.color = '#00aa00'; break;
                case '3': state.color = '#00aaaa'; break;
                case '4': state.color = '#aa0000'; break;
                case '5': state.color = '#aa00aa'; break;
                case '6': state.color = '#ffaa00'; break;
                case '7': state.color = '#aaaaaa'; break;
                case '8': state.color = '#555555'; break;
                case '9': state.color = '#5555ff'; break;
                case 'a': state.color = '#55ff55'; break;
                case 'b': state.color = '#55ffff'; break;
                case 'c': state.color = '#ff5555'; break;
                case 'd': state.color = '#ff55ff'; break;
                case 'e': state.color = '#ffff55'; break;
                case 'f': state.color = '#ffffff'; break;
                case 'l': state.bold = true; break;
                case 'o': state.italic = true; break;
                case 'n': state.underline = true; break;
                case 'm': state.strike = true; break;
                case 'k': state.magic = true; break;
                case 'r': state = {color:null,bold:false,italic:false,underline:false,strike:false,magic:false}; break;
                default: break;
            }
            i++; // skip code char
            continue;
        }
        // normal char
        flushChar(ch);
    }
    return out.join('');
}

function escapeHtml(s){
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function escapeAttr(s){
    return (s||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Serialize an editor (contenteditable) back to MC-coded string
function serializeEditor(editor){
    const segments = [];

    const hasDecorationInChain = (el, token) => {
        let cur = el;
        while (cur && cur !== editor && cur.nodeType === Node.ELEMENT_NODE) {
            const tag = (cur.tagName || '').toLowerCase();
            if (token === 'underline' && (tag === 'u' || tag === 'ins')) return true;
            if (token === 'line-through' && (tag === 's' || tag === 'strike' || tag === 'del')) return true;

            const dec = (cur.style && (cur.style.textDecorationLine || cur.style.textDecoration)) || '';
            if (dec && dec.includes(token)) return true;

            const cs = window.getComputedStyle(cur);
            const cdec = (cs.textDecorationLine || cs.textDecoration || '');
            if (cdec.includes(token)) return true;

            cur = cur.parentElement;
        }
        return false;
    };

    const hasMagicInChain = (el) => {
        let cur = el;
        while (cur && cur !== editor && cur.nodeType === Node.ELEMENT_NODE) {
            if ((cur.getAttribute && cur.getAttribute('data-magic')) || (cur.classList && cur.classList.contains('mc-magic-editor'))) {
                return true;
            }
            const cs = window.getComputedStyle(cur);
            if ((cs.filter || '').includes('blur')) return true;
            cur = cur.parentElement;
        }
        return false;
    };

    const styleOf = (el) => {
        const style = window.getComputedStyle(el || editor);
        const hex = rgbToHex(style.color);
        const mcColor = hex ? mcColorMap[hex.toLowerCase()] || '§x'+[...hex.slice(1)].map(c=>'§'+c).join('') : null;
        const weight = style.fontWeight;
        const dec = style.textDecorationLine || style.textDecoration || '';
        return {
            color: mcColor,
            bold: (weight === 'bold' || parseInt(weight, 10) >= 600),
            italic: style.fontStyle === 'italic',
            underline: dec.includes('underline') || hasDecorationInChain(el, 'underline'),
            strike: dec.includes('line-through') || hasDecorationInChain(el, 'line-through'),
            magic: (style.filter || '').includes('blur') || hasMagicInChain(el)
        };
    };

    const sameStyle = (a, b) =>
        a.color === b.color &&
        a.bold === b.bold &&
        a.italic === b.italic &&
        a.underline === b.underline &&
        a.strike === b.strike &&
        a.magic === b.magic;

    const appendSegment = (text, style) => {
        if(!text) return;
        const last = segments[segments.length - 1];
        if(last && sameStyle(last.style, style)){
            last.text += text;
        } else {
            segments.push({ text, style });
        }
    };

    const walk = (node, parentEl) => {
        if(node.nodeType === Node.TEXT_NODE){
            const el = parentEl || node.parentElement || editor;
            let text = node.nodeValue || '';

            // If this text node is inside a per-character magic span, preserve original char.
            if(el && el.classList && el.classList.contains('mc-magic-editor')){
                const orig = el.getAttribute && el.getAttribute('data-original');
                if(orig !== null && orig !== undefined) text = orig;
            }

            if(!text) return;
            const st = styleOf(el);

            // Explicit magic wrapper markers should force magic style on the run.
            if(el && el.getAttribute && el.getAttribute('data-magic')) st.magic = true;
            if(el && el.classList && el.classList.contains('mc-magic-editor')) st.magic = true;

            appendSegment(text, st);
            return;
        }

        if(node.nodeType === Node.ELEMENT_NODE){
            if(node.hasAttribute('data-right-marker')){appendSegment(RIGHT_MARKER,styleOf(node.parentElement));return;}
            node.childNodes.forEach(c => walk(c, node));
            return;
        }

        node.childNodes && node.childNodes.forEach(c => walk(c, parentEl));
    };

    editor.childNodes.forEach(n => walk(n, editor));
    if(segments.length === 0) return '';

    const hasAnyFormat = (s) => s.bold || s.italic || s.underline || s.strike || s.magic;
    const active = { color: null, bold: false, italic: false, underline: false, strike: false, magic: false };
    let out = '';

    const emitFormats = (s) => {
        if(s.bold) out += '§l';
        if(s.italic) out += '§o';
        if(s.underline) out += '§n';
        if(s.strike) out += '§m';
        if(s.magic) out += '§k';
    };

    for(const seg of segments){
        const s = seg.style;

        // If formatting gets removed, reset once and rebuild state (efficient, not per character).
        const removedFormat =
            (active.bold && !s.bold) ||
            (active.italic && !s.italic) ||
            (active.underline && !s.underline) ||
            (active.strike && !s.strike) ||
            (active.magic && !s.magic);

        if(removedFormat || (active.color && !s.color)){
            out += '§r';
            active.color = null;
            active.bold = false;
            active.italic = false;
            active.underline = false;
            active.strike = false;
            active.magic = false;
        }

        if(s.color && s.color !== active.color){
            out += s.color;
            active.color = s.color;
            // color code resets previous effects in Minecraft, so re-emit current effects for this run
            emitFormats(s);
            active.bold = s.bold;
            active.italic = s.italic;
            active.underline = s.underline;
            active.strike = s.strike;
            active.magic = s.magic;
        } else {
            if(s.bold && !active.bold){ out += '§l'; active.bold = true; }
            if(s.italic && !active.italic){ out += '§o'; active.italic = true; }
            if(s.underline && !active.underline){ out += '§n'; active.underline = true; }
            if(s.strike && !active.strike){ out += '§m'; active.strike = true; }
            if(s.magic && !active.magic){ out += '§k'; active.magic = true; }
            if(!active.color && s.color){
                out += s.color;
                active.color = s.color;
            }
        }

        out += seg.text;
    }

    // Final normalization pass removes redundant effect codes and unnecessary resets.
    return compactMcFormatting(out);
}

function compactMcFormatting(input){
    if(!input) return '';

    const isColor = c => /[0-9a-f]/.test(c);
    const isEffect = c => /[klmno]/.test(c);
    const resetState = () => ({ color:null, k:false, l:false, m:false, n:false, o:false });

    let state = resetState();
    let out = '';

    for(let i = 0; i < input.length; i++){
        const ch = input[i];
        if(ch !== '§' || i + 1 >= input.length){
            out += ch;
            continue;
        }

        const hex=input.slice(i,i+14);if(/^§x(?:§[0-9a-f]){6}$/i.test(hex)){out+=hex;state=resetState();state.color=hex;i+=13;continue;}
        const code = input[i + 1].toLowerCase();
        i++;

        if(code === 'r'){
            // Only emit reset if there is active formatting to reset.
            if(state.color !== null || state.k || state.l || state.m || state.n || state.o){
                out += '§r';
                state = resetState();
            }
            continue;
        }

        if(isColor(code)){
            // Color code resets effects; only emit when it changes or effects are active.
            const needsColor = state.color !== code || state.k || state.l || state.m || state.n || state.o;
            if(needsColor){
                out += '§' + code;
                state = resetState();
                state.color = code;
            }
            continue;
        }

        if(isEffect(code)){
            if(!state[code]){
                out += '§' + code;
                state[code] = true;
            }
            continue;
        }

        // Preserve any unknown formatting codes as-is.
        out += '§' + code;
    }

    return out;
}

let activeTextEditor = null;
document.addEventListener('focusin', e=>{
    if(e.target && e.target.classList && e.target.classList.contains('editor-field')) activeTextEditor = e.target;
});

// toolbar behavior scoped to a wrapper
function setupToolbar(toolbar){
    const wrapper = toolbar.closest('.card') || toolbar.closest('.line-row') || toolbar.closest('#title-wrapper');
    // prefer the editor within the same wrapper; when toolbar is the global top toolbar, only title exists within its wrapper
    let editor = null;
    // For title, toolbar is in same section; we'll rely on focusing to determine the editor

    toolbar.querySelectorAll('button').forEach(btn=> btn.addEventListener('mousedown', e=> e.preventDefault()));

    toolbar.querySelectorAll('.color-swatch').forEach(s=> s.addEventListener('click', ()=>{
        const code = s.dataset.code;
        const target = activeTextEditor || document.getElementById('title');
        if(!target) return;
        // apply color to current selection in activeTextEditor
        // apply color to current selection in activeTextEditor
        document.execCommand('styleWithCSS', false, true);
        document.execCommand('foreColor', false, codeToCss(code));
        // update outline on the editor after applying a color — defer so computed style reflects the change
        setTimeout(()=> applyEditorOutline(target), 0);
        setTimeout(()=> scheduleAutoSave(), 0);
    }));

    toolbar.querySelectorAll('.eff-btn').forEach(b=> b.addEventListener('click', ()=>{
        const code = b.dataset.code;
        const target = activeTextEditor || document.getElementById('title');
        if(!target) return;
        switch(code){
            case '§l': document.execCommand('bold'); scheduleAutoSave(); break;
            case '§o': document.execCommand('italic'); scheduleAutoSave(); break;
            case '§n': document.execCommand('underline'); scheduleAutoSave(); break;
            case '§m': document.execCommand('strikeThrough'); scheduleAutoSave(); break;
            case '§k': toggleMagic(); scheduleAutoSave(); break;
            case '§r': // reset – remove inline styles in selection and stop/unwrap any magic
                // Remove formatting from selection
                document.execCommand('removeFormat');
                // visual feedback
                b.classList.add('spin');
                setTimeout(()=> b.classList.remove('spin'), 800);
                // apply outline update after DOM changes take effect
                setTimeout(()=> applyEditorOutline(target), 0);
                // stop any editor-side magic animations and restore original characters immediately
                setTimeout(()=>{
                    try{ stopEditorMagic(target); }catch(e){}
                    try{ unwrapDataMagicWrappers(target); }catch(e){}
                    try{ stopAllPreviewMagic(); }catch(e){}
                    try{ updatePreview(); }catch(e){}
                    try{ scheduleAutoSave(); }catch(e){}
                }, 0);
                break;
        }
    }));
}

function codeToCss(code){
    // code is like '§a' — return a CSS color string
    if(!code) return null;
    const c = code[1].toLowerCase();
    const map = {
        '0':'#000000','1':'#0000aa','2':'#00aa00','3':'#00aaaa','4':'#aa0000','5':'#aa00aa','6':'#ffaa00','7':'#aaaaaa',
        '8':'#555555','9':'#5555ff','a':'#55ff55','b':'#55ffff','c':'#ff5555','d':'#ff55ff','e':'#ffff55','f':'#ffffff'
    };
    return map[c] || null;
}

function toggleMagic(){
    const sel = window.getSelection();
    if(!sel.rangeCount) return;
    const range = sel.getRangeAt(0);
    // If the active editor already contains per-character magic spans, treat this as a global toggle-off
    // and restore the original text immediately (do not leave last-random characters behind).
    if(activeTextEditor && activeTextEditor.querySelectorAll('.mc-magic-editor').length > 0){
        stopEditorMagic(activeTextEditor);
        unwrapDataMagicWrappers(activeTextEditor);
        updatePreview();
        scheduleAutoSave();
        return;
    }
    // If selection contains existing magic spans, unwrap them (toggle off)
    let unwrappedAny = false;
    const existing = [...(activeTextEditor?.querySelectorAll('span[data-magic], .mc-magic-editor')||[])];
    for(const s of existing){
        if(range.intersectsNode(s)){
            // if this is an editor magic span or a wrapped char, restore original
            if(s.classList && s.classList.contains('mc-magic-editor')){
                const orig = s.getAttribute('data-original') || s.textContent || '';
                s.replaceWith(document.createTextNode(orig));
                unwrappedAny = true;
            } else if(s.getAttribute && s.getAttribute('data-magic')){
                // replace whole data-magic span with its text content
                // also ensure any mc-magic in the preview will be restored on next update
                s.replaceWith(document.createTextNode(s.textContent));
                unwrappedAny = true;
            }
        }
    }
    // After unwrapping, stop editor magic for this editor if there are no more mc-magic-editor spans
    if(activeTextEditor && activeTextEditor.querySelectorAll('.mc-magic-editor').length === 0){
        stopEditorMagic(activeTextEditor);
        unwrapDataMagicWrappers(activeTextEditor);
    }
    // If we just toggled existing magic off, do not immediately wrap again.
    if(unwrappedAny){
        updatePreview();
        scheduleAutoSave();
        return;
    }

    // Otherwise, wrap selection into a data-magic span and then split into per-character spans for animation
    const span = document.createElement('span');
    span.setAttribute('data-magic','true');
    // insert extracted contents into span temporarily
    const contents = range.extractContents();
    span.appendChild(contents);
    // Now convert each text node inside span into individual character spans
    const walker = document.createTreeWalker(span, NodeFilter.SHOW_TEXT, null, false);
    const textNodes = [];
    while(walker.nextNode()) textNodes.push(walker.currentNode);
    textNodes.forEach(tn=>{
        const txt = tn.nodeValue || '';
        const frag = document.createDocumentFragment();
        for(const ch of txt){
            const cspan = document.createElement('span');
            cspan.className = 'mc-magic-editor';
            cspan.setAttribute('data-original', ch);
            cspan.textContent = ch;
            frag.appendChild(cspan);
        }
        tn.replaceWith(frag);
    });
    // insert the new complex span back into the range
    range.insertNode(span);
    // start animation for the containing editor
    const editorRoot = activeTextEditor || span.closest('.editor-field') || document.getElementById('title');
    startEditorMagic(editorRoot);
    // update preview so newly-magic text appears obfuscated there as well
    updatePreview();
}

// Apply or toggle rainbow color on the current selection (editor). Wrap selection in
// a span.mc-rainbow-editor so the editor shows animated rainbow text. Toggling off
// restores original text.
/* Rainbow option removed: applyRainbowToSelection() deleted */

// --- Magic animation managers ---
// Separate timer maps for preview and editor so they can be managed independently
const _previewMagicTimers = new Map();
const _editorMagicTimers = new Map();

function stopAllPreviewMagic(){
    for(const [el, id] of _previewMagicTimers.entries()){
        clearInterval(id);
        try{
            const orig = el.getAttribute && el.getAttribute('data-original');
            if(orig !== null && orig !== undefined) el.textContent = orig;
        }catch(e){}
    }
    _previewMagicTimers.clear();
    // also ensure any remaining .mc-magic elements in the preview are restored to their original text
    try{
        document.querySelectorAll('#sb-preview .mc-magic').forEach(el=>{
            try{
                const o = el.getAttribute && el.getAttribute('data-original');
                if(o !== null && o !== undefined) el.textContent = o;
            }catch(e){}
        });
    }catch(e){}
}

function stopEditorMagic(editor){
    if(!editor) return;
    const id = _editorMagicTimers.get(editor);
    if(id){ clearInterval(id); _editorMagicTimers.delete(editor); }
    // restore original characters for any remaining mc-magic-editor spans
    editor.querySelectorAll('.mc-magic-editor').forEach(s=>{
        const orig = s.getAttribute('data-original') || s.textContent || '';
        const txt = document.createTextNode(orig);
        s.replaceWith(txt);
    });
}

// Unwrap any wrapper spans with data-magic="true" inside the editor by replacing them with their text content
function unwrapDataMagicWrappers(editor){
    if(!editor) return;
    const wrappers = [...editor.querySelectorAll('span[data-magic]')];
    wrappers.forEach(w=>{
        try{
            const txt = document.createTextNode(w.textContent || '');
            w.replaceWith(txt);
        }catch(e){}
    });
}

function randomObfCharFromFontpack(){
    // Use a wide range of characters to better approximate the font glyph set
    const parts = [];
    // printable ASCII
    for(let i=33;i<127;i++) parts.push(String.fromCharCode(i));
    // Latin-1 Supplement block (¡ - ÿ)
    for(let i=161;i<256;i++) parts.push(String.fromCharCode(i));
    const chars = parts.join('');
    return chars.charAt(Math.floor(Math.random()*chars.length));
}

function startPreviewMagic(root){
    if(!root) return;
    const els = root.querySelectorAll('.mc-magic');
    els.forEach(el=>{
        if(_previewMagicTimers.has(el)) return;
        const orig = el.getAttribute('data-original') || el.textContent || '';
        const len = Math.max(1, orig.length);
        const id = setInterval(()=>{
            let s = '';
            for(let i=0;i<len;i++) s += randomObfCharFromFontpack();
            el.textContent = s;
        }, 40);
        _previewMagicTimers.set(el, id);
    });
}

function startEditorMagic(editor){
    if(!editor) return;
    // avoid creating multiple timers for same editor
    if(_editorMagicTimers.has(editor)) return;
    const els = editor.querySelectorAll('.mc-magic-editor');
    if(els.length === 0) return;
    // interval updates all mc-magic-editor spans inside this editor
    const id = setInterval(()=>{
        els.forEach(s=>{
            // replace with a single random character from fontpack
            s.textContent = randomObfCharFromFontpack();
        });
    }, 40);
    _editorMagicTimers.set(editor, id);
}


const lineTemplate="\n        <div class=\"row-top\">\n            <div class=\"editor-field\" role=\"textbox\" aria-label=\"Scoreboard line\" contenteditable=\"true\" spellcheck=\"false\"></div>\n            <button class=\"remove\" aria-label=\"Remove line\" type=\"button\">✖</button>\n        </div>\n        <div class=\"toolbar\">\n            <div class=\"color-row\">\n                <!-- Ordered dark -> light following spectrum-ish order -->\n                <button class=\"color-swatch\" aria-label=\"Black\" title=\"Black\" data-code=\"§0\" style=\"background:#000000\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Gray\" title=\"Dark Gray\" data-code=\"§8\" style=\"background:#555555\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Blue\" title=\"Dark Blue\" data-code=\"§1\" style=\"background:#0000AA\"></button>\n                <button class=\"color-swatch\" aria-label=\"Blue\" title=\"Blue\" data-code=\"§9\" style=\"background:#5555FF\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Aqua\" title=\"Dark Aqua\" data-code=\"§3\" style=\"background:#00AAAA\"></button>\n                <button class=\"color-swatch\" aria-label=\"Aqua\" title=\"Aqua\" data-code=\"§b\" style=\"background:#55FFFF\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Green\" title=\"Dark Green\" data-code=\"§2\" style=\"background:#00AA00\"></button>\n                <button class=\"color-swatch\" aria-label=\"Green\" title=\"Green\" data-code=\"§a\" style=\"background:#55FF55\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Red\" title=\"Dark Red\" data-code=\"§4\" style=\"background:#AA0000\"></button>\n                <button class=\"color-swatch\" aria-label=\"Red\" title=\"Red\" data-code=\"§c\" style=\"background:#FF5555\"></button>\n                <button class=\"color-swatch\" aria-label=\"Gold\" title=\"Gold\" data-code=\"§6\" style=\"background:#FFAA00\"></button>\n                <button class=\"color-swatch\" aria-label=\"Yellow\" title=\"Yellow\" data-code=\"§e\" style=\"background:#FFFF55\"></button>\n                <button class=\"color-swatch\" aria-label=\"Dark Purple\" title=\"Dark Purple\" data-code=\"§5\" style=\"background:#AA00AA\"></button>\n                <button class=\"color-swatch\" aria-label=\"Light Purple\" title=\"Light Purple\" data-code=\"§d\" style=\"background:#FF55FF\"></button>\n                <button class=\"color-swatch\" aria-label=\"Gray\" title=\"Gray\" data-code=\"§7\" style=\"background:#AAAAAA\"></button>\n                <button class=\"color-swatch\" aria-label=\"White\" title=\"White\" data-code=\"§f\" style=\"background:#FFFFFF;border:1px solid #ddd\"></button>\n            </div>\n                <div class=\"effects\">\n                    <button class=\"eff-btn\" data-code=\"§l\" title=\"Vet (Bold)\" aria-label=\"Vet (Bold)\">\n                        <span class=\"font-icon bold-icon\">B</span>\n                    </button>\n                    <button class=\"eff-btn\" data-code=\"§o\" title=\"Schuingedrukt (Italic)\" aria-label=\"Schuingedrukt (Italic)\">\n                        <span class=\"font-icon italic-icon\">Z</span>\n                    </button>\n                    <button class=\"eff-btn\" data-code=\"§n\" title=\"Onderlijn (Underline)\" aria-label=\"Onderlijn (Underline)\">\n                        <span class=\"font-icon underline-icon\">U</span>\n                    </button>\n                    <button class=\"eff-btn\" data-code=\"§m\" title=\"Doorstreep (Strikethrough)\" aria-label=\"Doorstreep (Strikethrough)\">\n                        <span class=\"font-icon strike-icon\">A</span>\n                    </button>\n                    <button class=\"eff-btn\" data-code=\"§k\" title=\"Magic (Verberg/obfuscate)\" aria-label=\"Magic (Obfuscate)\">\n                        <svg class=\"star-icon\" viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M8 1.5l1.6 3.3 3.6.5-2.6 2.2.6 3.6L8 10.6 4.8 11.1l.6-3.6L2.8 5.3l3.6-.5L8 1.5z\"/></svg>\n                    </button>\n                    <button class=\"eff-btn reset-btn\" data-code=\"§r\" title=\"Reset\" aria-label=\"Reset\">\n                        <svg class=\"reset-icon\" viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n                            <path d=\"M21 12a9 9 0 1 0-9 9\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>\n                            <polyline points=\"23 3 23 9 17 9\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>\n                        </svg>\n                    </button>\n                </div>\n        </div>\n    ";
function mount(parent,{value='',label='Nametag text',onChange=()=>{},singleLine=false}={}){
 const row=document.createElement('div');row.className='line-row minecraft-text-editor';row.innerHTML=lineTemplate;parent.append(row);
 const editor=row.querySelector('.editor-field');editor.setAttribute('aria-label',label);editor.setAttribute('aria-multiline','false');editor.innerHTML=mcToHtml(normalizeLegacy(value));row.querySelector('.remove').remove();
 const changed=()=>{let text=serializeEditor(editor);if(singleLine&&/[\r\n\u2028\u2029]/.test(text)){text=text.replace(/[\r\n\u2028\u2029]+/g,' ');editor.innerHTML=mcToHtml(text);}onChange(text);};
 changes.set(editor,{change:changed,preview:changed});
 editor.addEventListener('input',changed);
 editor.addEventListener('keydown',event=>{if(singleLine&&event.key==='Enter')event.preventDefault();});
 editor.addEventListener('beforeinput',event=>{if(singleLine&&['insertParagraph','insertLineBreak'].includes(event.inputType))event.preventDefault();});
 editor.addEventListener('paste',event=>{if(!singleLine)return;event.preventDefault();document.execCommand('insertText',false,(event.clipboardData.getData('text/plain')||'').replace(/[\r\n\u2028\u2029]+/g,' '));changed();});
 editor.addEventListener('drop',event=>{if(singleLine)event.preventDefault();});
 attachDefaultWhiteBehavior(editor);applyEditorOutline(editor);setupToolbar(row.querySelector('.toolbar'));row.querySelectorAll('button').forEach(b=>b.type='button');
 return{element:editor,value:()=>serializeEditor(editor),dispose:()=>{stopEditorMagic(editor);changes.delete(editor);if(activeTextEditor===editor)activeTextEditor=null;row.remove();}};
}
function normalizeLegacy(value){return value.replace(/&#([0-9a-f]{6})/gi,(_,hex)=>'§x'+[...hex].map(c=>'§'+c).join('')).replace(/&([0-9a-fk-orx])/gi,'§$1');}
return{applyEditorOutline,attachDefaultWhiteBehavior,mcToHtml,serializeEditor,compactMcFormatting,setupToolbar,stopEditorMagic,stopAllPreviewMagic,startPreviewMagic,startEditorMagic,stripMcCodes,escapeHtml,escapeAttr,codeToCss,unwrapDataMagicWrappers,lineTemplate,mount,normalizeLegacy,setDefaults:hooks=>defaults=hooks};
})();
