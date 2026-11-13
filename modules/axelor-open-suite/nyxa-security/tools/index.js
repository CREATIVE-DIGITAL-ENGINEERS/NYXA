// Builds domain and menu indexes from the source tree. Usage: node index.js <repoRoot> <outDir>
'use strict';
var fs = require('fs');
var path = require('path');
var root = process.argv[2], out = process.argv[3];

function walk(dir, pred, acc) {
  acc = acc || [];
  var entries;
  try { entries = fs.readdirSync(dir); } catch (e) { return acc; }
  entries.forEach(function (n) {
    if (n === 'build' || n === 'node_modules' || n === 'bin' || n === 'test') return;
    var p = path.join(dir, n), st = fs.statSync(p);
    if (st.isDirectory()) walk(p, pred, acc); else if (pred(p)) acc.push(p);
  });
  return acc;
}
function attr(tag, name) {
  var m = tag.match(new RegExp('\\s' + name + '="([^"]*)"'));
  return m ? m[1] : null;
}

// Domains: entity FQCN -> { fields: {name: {kind, ref}} }, merged across modules.
var domains = {};
walk(path.join(root, 'modules'), function (p) { return /[\\/]src[\\/]main[\\/]resources[\\/]domains[\\/][^\\/]+\.xml$/.test(p); })
  .forEach(function (f) {
    var x = fs.readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    var pkg = (x.match(/<module [^>]*package="([^"]+)"/) || [])[1];
    if (!pkg) return;
    var re = /<entity\b([^>]*)>([\s\S]*?)<\/entity>/g, m;
    while ((m = re.exec(x))) {
      var name = attr(m[1], 'name');
      if (!name) continue;
      var fq = pkg + '.' + name;
      var d = domains[fq] = domains[fq] || { fields: {}, files: [] };
      d.files.push(path.relative(root, f));
      var fr = /<(many-to-one|one-to-many|many-to-many|one-to-one|string|integer|long|decimal|boolean|date|datetime|time|binary|enum)\b([^>]*?)\/?>/g, g;
      while ((g = fr.exec(m[2]))) {
        var fn = attr(g[2], 'name');
        if (fn) d.fields[fn] = { kind: g[1], ref: attr(g[2], 'ref') };
      }
    }
  });
// Resolve short refs (same package) to FQCN.
Object.keys(domains).forEach(function (fq) {
  var pkg = fq.slice(0, fq.lastIndexOf('.'));
  Object.keys(domains[fq].fields).forEach(function (k) {
    var r = domains[fq].fields[k].ref;
    if (r && r.indexOf('.') < 0) domains[fq].fields[k].ref = pkg + '.' + r;
  });
});

// Menus: name -> {parent, title, if, ifModule, groups, hidden, action, files}
var menus = {};
walk(path.join(root, 'modules'), function (p) { return /[\\/]src[\\/]main[\\/]resources[\\/]views[\\/][^\\/]+\.xml$/.test(p); })
  .forEach(function (f) {
    var x = fs.readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    var re = /<menuitem\b([^>]*?)\/?>/g, m;
    while ((m = re.exec(x))) {
      var name = attr(m[1], 'name');
      if (!name) continue;
      var e = menus[name] = menus[name] || { files: [] };
      ['parent', 'title', 'if', 'if-module', 'groups', 'hidden', 'action'].forEach(function (a) {
        var v = attr(m[1], a); if (v !== null) e[a] = v;
      });
      e.files.push(path.relative(root, f));
    }
  });

// Action-views: name -> model, so a menu's action tells which model it opens.
var actions = {};
walk(path.join(root, 'modules'), function (p) { return /[\\/]src[\\/]main[\\/]resources[\\/]views[\\/][^\\/]+\.xml$/.test(p); })
  .forEach(function (f) {
    var x = fs.readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    var re = /<action-view\b([^>]*?)>/g, m;
    while ((m = re.exec(x))) {
      var name = attr(m[1], 'name'), model = attr(m[1], 'model');
      if (name && model) actions[name] = model;
    }
  });

// Owning module of each entity (first domain file), for reporting.
Object.keys(domains).forEach(function (fq) {
  var f = domains[fq].files[0].split(path.sep).join('/');
  var m = f.match(/modules\/[^/]+\/([^/]+)/);
  domains[fq].module = m ? m[1] : null;
});

fs.writeFileSync(path.join(out, 'domains.json'), JSON.stringify(domains));
fs.writeFileSync(path.join(out, 'menus.json'), JSON.stringify(menus));
fs.writeFileSync(path.join(out, 'actions.json'), JSON.stringify(actions));
console.log('entities: ' + Object.keys(domains).length + ', menus: ' + Object.keys(menus).length +
  ', action-views: ' + Object.keys(actions).length);
