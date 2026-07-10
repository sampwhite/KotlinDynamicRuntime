package com.dynamicruntime.common.portal

/**
 * The portal's a single HTML page, rendered as a Kotlin string rather than a bundled resource file (the house
 * style: what would elsewhere be a static asset is a Kotlin declaration). Self-contained -- inline CSS and
 * vanilla JS, no build step, no external assets. [render] injects the frontend bootstrap config
 * (`window.kdrCfg`, the context roots by focus) so the page's JS builds backend URLs from the live roots:
 * it lists endpoints via the API root's `/schema/endpoints` and renders a form per endpoint.
 *
 * Written as a multi-dollar (`$$`) string so a bare `$` is literal; the interpolated values are the bootstrap
 * config (the `cfgJson` parameter) and the context-root-relative `/schema/endpoints` path.
 */
object PortalPage {
    fun render(cfgJson: String): String = $$"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>KDR Endpoint Portal</title>
<style>
  :root { color-scheme: light; } /* the portal is an all-light design; opt out of dark-mode UA control colors */
  * { box-sizing: border-box; }
  body {
    margin: 0; font: 15px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    background: #f6f7f9; color: #1c2126;
  }
  header {
    padding: 20px 28px; background: #1c2530; color: #fff;
    border-bottom: 3px solid #3b82f6;
  }
  header h1 { margin: 0 0 4px; font-size: 20px; }
  header .sub { margin: 0; opacity: .75; font-size: 13px; }
  header nav {
    margin-top: 14px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  }
  header nav a {
    color: #cdd7e2; text-decoration: none; font-size: 13px; font-weight: 600;
    padding: 6px 12px; border-radius: 6px; background: rgba(255, 255, 255, .06);
  }
  header nav a:hover { background: rgba(255, 255, 255, .14); color: #fff; }
  header nav a.active { background: #3b82f6; color: #fff; }
  header nav a.active:hover { background: #2f6fd6; }
  header button {
    background: #3b82f6; color: #fff; border: 0; border-radius: 6px;
    padding: 7px 14px; font-size: 13px; cursor: pointer;
  }
  header button:hover { background: #2f6fd6; }
  main { max-width: 860px; margin: 24px auto; padding: 0 16px; }
  .muted { color: #6b7280; }
  .error { color: #b91c1c; }
  .card {
    background: #fff; border: 1px solid #e2e6eb; border-radius: 10px;
    margin-bottom: 18px; overflow: hidden;
  }
  .card-head {
    display: flex; align-items: center; gap: 10px;
    padding: 12px 16px; border-bottom: 1px solid #eef1f4; background: #fbfcfd;
  }
  .badge {
    font: 600 11px/1 ui-monospace, monospace; letter-spacing: .5px;
    padding: 5px 8px; border-radius: 5px; color: #fff;
  }
  .badge-get { background: #2563eb; }
  .badge-post { background: #16a34a; }
  .badge-put { background: #d97706; }
  .path { font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 600; }
  .kind { margin-left: auto; font-size: 11px; color: #9aa4af; text-transform: uppercase; letter-spacing: .5px; }
  .endpoint-desc { margin: 0; padding: 10px 16px 0; font-size: 13px; color: #4b5563; }
  form { padding: 14px 16px; }
  .fields { display: flex; flex-direction: column; gap: 12px; }
  .row { display: flex; flex-direction: column; gap: 4px; }
  .row label { font-size: 13px; font-weight: 600; }
  .row input[type=text], .row input[type=number], .row select, .row textarea {
    padding: 7px 9px; border: 1px solid #cfd6dd; border-radius: 6px; font: inherit; background: #fff; color: #1c2126;
  }
  .row textarea { font: 13px ui-monospace, monospace; resize: vertical; }
  .row input[type=checkbox] { width: 18px; height: 18px; }
  .hint { margin: 0; font-size: 12px; color: #6b7280; }
  fieldset { border: 1px solid #e2e6eb; border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 10px; }
  legend { font-size: 12px; font-weight: 700; color: #374151; padding: 0 6px; }
  .actions { margin-top: 14px; }
  .actions button {
    background: #1c2530; color: #fff; border: 0; border-radius: 6px;
    padding: 8px 18px; font-size: 13px; font-weight: 600; cursor: pointer;
  }
  .actions button:hover { background: #33404f; }
  .response {
    margin: 0; padding: 14px 16px; background: #0f172a; color: #d7e3f4;
    font: 12.5px/1.5 ui-monospace, monospace; white-space: pre-wrap; word-break: break-word;
    border-top: 1px solid #eef1f4;
  }
  .response.err { background: #3b0d0d; color: #fbdada; }
</style>
</head>
<body>
<header>
  <h1>KDR Endpoint Portal</h1>
  <p class="sub">Form-based console for the endpoints served by this instance.</p>
  <nav>
    <a class="active" href="#">Endpoint Portal</a>
    <a id="webappLink" href="#">Webapp</a>
    <button id="reload" type="button">Reload endpoints</button>
  </nav>
</header>
<main id="app"><p class="muted">Loading endpoints…</p></main>
<script>window.kdrCfg = $${cfgJson};</script>
<script>
"use strict";
// Backend URLs are built from the injected bootstrap config (window.kdrCfg): the context roots keyed by
// focus. The endpoint catalog and every endpoint call go under the API root. The catalog returns each
// endpoint's input schema plus a shared `$defs`; the page resolves the `$ref`s against `$defs` itself.
var CFG = (window.kdrCfg && window.kdrCfg.contextRoots) || {};
var API_ROOT = '/' + (CFG.api || '');
var ENDPOINTS_API = API_ROOT + '$${PTL.endpointsApi}';
var app = document.getElementById('app');
document.getElementById('reload').addEventListener('click', load);
// The webapp lives under its own context root (focus "app"); build the menu link from the injected config
// so it tracks the configured root rather than hardcoding it.
document.getElementById('webappLink').href = '/' + (CFG.app || 'wa');

function el(tag, cls, text) {
  var e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

async function load() {
  app.innerHTML = '';
  app.appendChild(el('p', 'muted', 'Loading endpoints…'));
  try {
    var res = await fetch(ENDPOINTS_API, { headers: { 'Accept': 'application/json' } });
    var body = await res.json();
    // /schema/endpoints is a general endpoint: its result holds the endpoint renderings and a shared `$defs`
    // that the renderings' `$ref`s bind to.
    var results = (body && body.results) || {};
    var defs = results['$defs'] || {};
    var eps = results.endpoints || [];
    var endpoints = eps.map(function (it) {
      return {
        path: it.path,
        method: it.method,
        kind: it.kind,
        namespace: it.namespace,
        description: it.description,
        // Resolve the input schema's `$ref`s against `$defs`, flattening to form-field descriptors.
        fields: describeFields(it.inputSchema, defs, 0)
      };
    });
    render(endpoints);
  } catch (e) {
    app.innerHTML = '';
    app.appendChild(el('p', 'error', 'Failed to load endpoints: ' + String(e)));
  }
}

// Follows a chain of `$ref`s (resolving against `defs`) to a concrete schema node; returns {} when a ref
// cannot be resolved. Bounded hops guard against a ref cycle.
function resolveRef(node, defs) {
  var hops = 0;
  while (node && node['$ref'] && hops++ < 50) {
    var ref = node['$ref'];
    var prefix = '#/$defs/';
    var name = ref.indexOf(prefix) === 0 ? ref.substring(prefix.length) : ref;
    node = defs[name] || {};
  }
  return node || {};
}

// Flattens an object schema's properties into form-field descriptors (name/type/required/description/default/
// format/options; nested `fields` for objects, `itemType` for arrays), resolving `$ref`s against `defs`.
// Bounded depth guards a self-referential schema.
function describeFields(schema, defs, depth) {
  schema = resolveRef(schema, defs);
  var props = schema.properties;
  if (depth > 20 || !props) return [];
  var required = schema.required || [];
  var out = [];
  for (var name in props) {
    if (!Object.prototype.hasOwnProperty.call(props, name)) continue;
    var raw = props[name];
    var vt = resolveRef(raw, defs);
    var field = {
      name: name,
      required: required.indexOf(name) >= 0,
      description: (raw && raw.description) || vt.description,
      type: vt.type || 'string'
    };
    if (vt['default'] !== undefined) field['default'] = vt['default'];
    if (vt.format) field.format = vt.format;
    if (vt.options) field.options = vt.options;
    if (vt.type === 'object' && vt.properties) field.fields = describeFields(vt, defs, depth + 1);
    if (vt.type === 'array') field.itemType = (vt.items && resolveRef(vt.items, defs).type) || 'object';
    out.push(field);
  }
  return out;
}

function render(endpoints) {
  app.innerHTML = '';
  if (!endpoints.length) { app.appendChild(el('p', 'muted', 'No endpoints registered.')); return; }
  for (var i = 0; i < endpoints.length; i++) app.appendChild(renderEndpoint(endpoints[i]));
}

function renderEndpoint(ep) {
  var card = el('section', 'card');
  var head = el('div', 'card-head');
  head.appendChild(el('span', 'badge badge-' + ep.method.toLowerCase(), ep.method));
  head.appendChild(el('span', 'path', ep.path));
  head.appendChild(el('span', 'kind', ep.kind));
  card.appendChild(head);

  if (ep.description) card.appendChild(el('p', 'endpoint-desc', ep.description));

  var form = document.createElement('form');
  var fieldsWrap = el('div', 'fields');
  var fields = ep.fields || [];
  if (!fields.length) fieldsWrap.appendChild(el('p', 'muted', 'No input fields.'));
  for (var i = 0; i < fields.length; i++) renderField(fields[i], '', fieldsWrap);
  form.appendChild(fieldsWrap);

  var actions = el('div', 'actions');
  var submit = document.createElement('button');
  submit.type = 'submit';
  submit.textContent = 'Send ' + ep.method;
  actions.appendChild(submit);
  form.appendChild(actions);

  var out = el('pre', 'response');
  out.style.display = 'none';

  card.appendChild(form);
  card.appendChild(out);
  form.addEventListener('submit', function (e) { e.preventDefault(); send(ep, form, out); });
  return card;
}

function renderField(field, prefix, container) {
  var fullName = prefix ? prefix + '.' + field.name : field.name;

  if (field.fields && field.fields.length) {
    var fs = document.createElement('fieldset');
    fs.appendChild(el('legend', null, field.name + (field.required ? ' *' : '')));
    if (field.description) fs.appendChild(el('p', 'hint', field.description));
    for (var i = 0; i < field.fields.length; i++) renderField(field.fields[i], fullName, fs);
    container.appendChild(fs);
    return;
  }

  var row = el('div', 'row');
  row.appendChild(el('label', null, field.name + (field.required ? ' *' : '')));

  var input;
  var t = field.type;
  if (field.options && field.options.length) {
    input = document.createElement('select');
    if (!field.required) input.appendChild(new Option('', ''));
    for (var i = 0; i < field.options.length; i++) {
      input.appendChild(new Option(field.options[i].label, field.options[i].value));
    }
    input.dataset.kind = 'string';
  } else if (t === 'boolean') {
    input = document.createElement('input'); input.type = 'checkbox'; input.dataset.kind = 'boolean';
  } else if (t === 'integer' || t === 'number') {
    input = document.createElement('input'); input.type = 'number';
    if (t === 'integer') input.step = '1';
    input.dataset.kind = 'number';
  } else if (t === 'array' || t === 'object') {
    input = document.createElement('textarea'); input.rows = 2;
    input.placeholder = (t === 'array') ? '[ … ] (JSON)' : '{ … } (JSON)';
    input.dataset.kind = 'json';
  } else {
    input = document.createElement('input'); input.type = 'text'; input.dataset.kind = 'string';
    if (field.format) input.placeholder = field.format;
  }
  input.dataset.name = fullName;
  input.dataset.required = field.required ? 'true' : 'false';

  if (field.default !== undefined && field.default !== null) {
    if (input.type === 'checkbox') input.checked = !!field.default;
    else input.value = (typeof field.default === 'object') ? JSON.stringify(field.default) : String(field.default);
  }

  row.appendChild(input);
  if (field.description) row.appendChild(el('p', 'hint', field.description));
  container.appendChild(row);
}

function gather(form) {
  var flat = {};
  var controls = form.querySelectorAll('[data-name]');
  for (var i = 0; i < controls.length; i++) {
    var c = controls[i];
    var name = c.dataset.name;
    var kind = c.dataset.kind;
    var required = c.dataset.required === 'true';
    var val;
    if (kind === 'boolean') {
      val = c.checked;
    } else {
      var raw = c.value;
      if (raw === '' || raw == null) {
        if (!required) continue;
        val = '';
      } else if (kind === 'number') {
        var n = Number(raw); val = isNaN(n) ? raw : n;
      } else if (kind === 'json') {
        try { val = JSON.parse(raw); } catch (e) { throw new Error('Field "' + name + '" is not valid JSON.'); }
      } else {
        val = raw;
      }
    }
    flat[name] = val;
  }
  return flat;
}

function expand(flat) {
  var root = {};
  var keys = Object.keys(flat);
  for (var i = 0; i < keys.length; i++) {
    var parts = keys[i].split('.');
    var node = root;
    for (var j = 0; j < parts.length - 1; j++) {
      if (node[parts[j]] == null) node[parts[j]] = {};
      node = node[parts[j]];
    }
    node[parts[parts.length - 1]] = flat[keys[i]];
  }
  return root;
}

function scalar(v) { return (typeof v === 'object') ? JSON.stringify(v) : String(v); }

async function send(ep, form, out) {
  out.style.display = 'block';
  out.className = 'response';

  var flat;
  try { flat = gather(form); }
  catch (e) { out.classList.add('err'); out.textContent = String(e.message || e); return; }

  var opts = { method: ep.method, headers: { 'Accept': 'application/json' } };
  var url = API_ROOT + ep.path;
  if (ep.method === 'GET') {
    var keys = Object.keys(flat);
    var pairs = [];
    for (var i = 0; i < keys.length; i++) {
      pairs.push(encodeURIComponent(keys[i]) + '=' + encodeURIComponent(scalar(flat[keys[i]])));
    }
    if (pairs.length) url += '?' + pairs.join('&');
  } else {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(expand(flat));
  }

  out.textContent = '… ' + ep.method + ' ' + url;
  try {
    var res = await fetch(url, opts);
    var text = await res.text();
    var pretty = text;
    try { pretty = JSON.stringify(JSON.parse(text), null, 2); } catch (e) { /* leave as text */ }
    out.classList.toggle('err', !res.ok);
    out.textContent = ep.method + ' ' + url + '  →  ' + res.status + '\n\n' + pretty;
  } catch (e) {
    out.classList.add('err');
    out.textContent = 'Request failed: ' + String(e);
  }
}

load();
</script>
</body>
</html>
"""
}
