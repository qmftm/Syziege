/* Shared Syziege web map: tiles, world selection, players, region overlay,
   and (for the admin view) chunk painting. Used by index.html and admin.html. */
window.SyziegeMap = (function () {
  'use strict';

  function create(options) {
    options = options || {};
    var interactiveRegions = !!options.interactiveRegions;

    var map = L.map('map', {
      crs: L.CRS.Simple,
      minZoom: -5,
      maxZoom: 5,
      zoomSnap: 0.5,
      attributionControl: false
    });

    var tileLayer = null;
    var regionFill = L.layerGroup().addTo(map);
    var regionEdges = L.layerGroup().addTo(map);
    var coreLayer = L.layerGroup().addTo(map);
    var paintPreview = L.layerGroup().addTo(map);
    var playerMarkers = {};
    var worlds = [];
    var currentWorld = null;
    var regionData = { types: [], claims: {}, cores: {} };
    var nationColors = {};
    var worldChangeCbs = [];
    var strokeCbs = [];

    var worldSel = document.getElementById('world');
    var coordsEl = document.getElementById('coords');
    var playersEl = document.getElementById('players');
    var playersHead = document.getElementById('playersHead');
    var legendItems = document.getElementById('legendItems');

    // Minecraft (x, z) -> Leaflet latLng under CRS.Simple (lat = -z).
    function toLatLng(x, z) { return L.latLng(-z, x); }
    function chunkBounds(cx, cz) {
      return L.latLngBounds(toLatLng(cx * 16, cz * 16), toLatLng(cx * 16 + 16, cz * 16 + 16));
    }
    function chunkAt(latlng) {
      return { x: Math.floor(latlng.lng / 16), z: Math.floor(-latlng.lat / 16) };
    }

    /* ---------- worlds & tiles ---------- */

    function setWorld(info) {
      currentWorld = info.name;
      if (tileLayer) { map.removeLayer(tileLayer); }
      tileLayer = L.tileLayer('/tiles/' + encodeURIComponent(info.name) + '/{x}_{y}.png', {
        tileSize: 512, minNativeZoom: 0, maxNativeZoom: 0, noWrap: true
      });
      tileLayer.addTo(map);
      map.setView(toLatLng(info.spawnX, info.spawnZ), 0);
      Object.keys(playerMarkers).forEach(function (n) {
        map.removeLayer(playerMarkers[n]); delete playerMarkers[n];
      });
      drawRegions();
      worldChangeCbs.forEach(function (cb) { cb(currentWorld); });
    }

    fetch('/api/worlds').then(function (r) { return r.json(); }).then(function (list) {
      worlds = list;
      list.forEach(function (w) {
        var opt = document.createElement('option');
        opt.value = w.name; opt.textContent = w.name;
        worldSel.appendChild(opt);
      });
      worldSel.onchange = function () {
        var info = worlds.find(function (w) { return w.name === worldSel.value; });
        if (info) { setWorld(info); }
      };
      if (list.length) { setWorld(list[0]); }
    });

    if (coordsEl) {
      map.on('mousemove', function (e) {
        coordsEl.textContent = Math.floor(e.latlng.lng) + ', ' + Math.floor(-e.latlng.lat);
      });
    }

    /* ---------- region overlay ---------- */

    function typeColor(id) {
      var t = regionData.types.find(function (x) { return x.id === id; });
      return t ? t.color : '#888888';
    }

    function typeName(id) {
      var t = regionData.types.find(function (x) { return x.id === id; });
      return t ? t.name : id;
    }

    // A region is drawn in its owning nation's color; if unowned, its type color.
    function fillColorForType(id) {
      var core = (regionData.cores || {})[id];
      if (core && core.owner && nationColors[core.owner]) {
        return nationColors[core.owner];
      }
      return typeColor(id);
    }

    function drawRegions() {
      regionFill.clearLayers();
      regionEdges.clearLayers();
      coreLayer.clearLayers();
      var chunks = (regionData.claims && regionData.claims[currentWorld]) || [];
      var lookup = {};
      chunks.forEach(function (c) { lookup[c.x + ',' + c.z] = c.type; });

      chunks.forEach(function (c) {
        var color = fillColorForType(c.type);
        L.rectangle(chunkBounds(c.x, c.z), {
          stroke: false, fillColor: color, fillOpacity: 0.35, interactive: false
        }).addTo(regionFill);
        // Draw a border edge only where the neighbor is a different region.
        edge(lookup, c, color, 1, 0, [c.x * 16 + 16, c.z * 16], [c.x * 16 + 16, c.z * 16 + 16]);
        edge(lookup, c, color, -1, 0, [c.x * 16, c.z * 16], [c.x * 16, c.z * 16 + 16]);
        edge(lookup, c, color, 0, -1, [c.x * 16, c.z * 16], [c.x * 16 + 16, c.z * 16]);
        edge(lookup, c, color, 0, 1, [c.x * 16, c.z * 16 + 16], [c.x * 16 + 16, c.z * 16 + 16]);
      });

      // Capture cores: a diamond marker at each region's core block.
      var cores = regionData.cores || {};
      Object.keys(cores).forEach(function (typeId) {
        var core = cores[typeId];
        if (core.world !== currentWorld) { return; }
        var tip = typeName(typeId) + ' 점령 코어';
        if (core.owner) { tip += ' · 소유: ' + core.owner; }
        if (typeof core.health === 'number') { tip += ' · 체력 ' + core.health; }
        L.marker(toLatLng(core.x, core.z), {
          icon: L.divIcon({
            className: 'core-marker',
            html: '<div style="color:' + typeColor(typeId) + '">◆</div>',
            iconSize: [18, 18], iconAnchor: [9, 9]
          }),
          interactive: true
        }).bindTooltip(tip, { direction: 'top' }).addTo(coreLayer);
      });

      updateLegend();
    }

    function edge(lookup, c, color, dx, dz, a, b) {
      if (lookup[(c.x + dx) + ',' + (c.z + dz)] === c.type) { return; }
      L.polyline([toLatLng(a[0], a[1]), toLatLng(b[0], b[1])], {
        color: color, weight: 2, opacity: 0.9, interactive: false
      }).addTo(regionEdges);
    }

    function updateLegend() {
      if (!legendItems) { return; }
      var counts = {};
      var chunks = (regionData.claims && regionData.claims[currentWorld]) || [];
      chunks.forEach(function (c) { counts[c.type] = (counts[c.type] || 0) + 1; });
      legendItems.innerHTML = '';
      if (!regionData.types.length) {
        legendItems.innerHTML = '<div class="item empty">등록된 지역이 없습니다</div>';
        return;
      }
      regionData.types.forEach(function (t) {
        var row = document.createElement('div');
        row.className = 'item';
        row.innerHTML = '<span class="sw" style="background:' + t.color + '"></span>' +
          '<span>' + escapeHtml(t.name) + '</span>' +
          '<span style="margin-left:auto;color:#6b7180">' + (counts[t.id] || 0) + '청크</span>';
        legendItems.appendChild(row);
      });
    }

    function refreshRegions() {
      var nationsReq = fetch('/api/nations').then(function (r) { return r.json(); }).then(function (list) {
        nationColors = {};
        list.forEach(function (n) { nationColors[n.name] = n.color; });
      }).catch(function () {});
      return Promise.resolve(nationsReq).then(function () {
        return fetch('/api/regions').then(function (r) { return r.json(); }).then(function (data) {
          regionData = data;
          drawRegions();
          return data;
        });
      });
    }
    refreshRegions();
    // Poll so ownership/health/color changes from capture combat show up live.
    setInterval(refreshRegions, 5000);

    /* ---------- players ---------- */

    var authRequiredCbs = [];
    function clearPlayers() {
      Object.keys(playerMarkers).forEach(function (n) { map.removeLayer(playerMarkers[n]); delete playerMarkers[n]; });
      if (playersEl) { playersEl.innerHTML = ''; }
      if (playersHead) { playersHead.textContent = '접속자'; }
    }

    function refreshPlayers() {
      fetch('/api/players').then(function (r) {
        if (r.status === 401) { clearPlayers(); authRequiredCbs.forEach(function (cb) { cb(); }); return null; }
        return r.json();
      }).then(function (list) {
        if (!list) { return; }
        var seen = {};
        list.forEach(function (p) {
          if (p.world !== currentWorld) { return; }
          seen[p.name] = true;
          var pos = toLatLng(p.x, p.z);
          if (playerMarkers[p.name]) {
            playerMarkers[p.name].setLatLng(pos);
          } else {
            playerMarkers[p.name] = L.circleMarker(pos, {
              radius: 6, color: '#fff', weight: 2, fillColor: '#e63946', fillOpacity: 1
            }).bindTooltip(p.name, { permanent: true, direction: 'right', offset: [8, 0],
              className: 'player-label' }).addTo(map);
          }
        });
        Object.keys(playerMarkers).forEach(function (n) {
          if (!seen[n]) { map.removeLayer(playerMarkers[n]); delete playerMarkers[n]; }
        });
        if (playersEl) { renderPlayerList(list); }
      }).catch(function () {});
    }

    function renderPlayerList(list) {
      if (playersHead) { playersHead.textContent = '접속자 ' + list.length + '명'; }
      playersEl.innerHTML = '';
      list.forEach(function (p) {
        var row = document.createElement('button');
        row.innerHTML = '<span>' + escapeHtml(p.name) + '</span>' +
          '<span class="pw">' + escapeHtml(p.world) + '</span>';
        row.onclick = function () {
          if (p.world === currentWorld) {
            map.setView(toLatLng(p.x, p.z), Math.max(map.getZoom(), 0));
          }
        };
        playersEl.appendChild(row);
      });
    }
    refreshPlayers();
    setInterval(refreshPlayers, 2000);

    /* ---------- admin painting ---------- */

    var painting = false, paintMode = false, stroke = null, previewColor = '#ffffff';

    function beginStroke() { stroke = {}; }
    function paintChunk(cx, cz) {
      var key = cx + ',' + cz;
      if (stroke[key]) { return; }
      stroke[key] = [cx, cz];
      L.rectangle(chunkBounds(cx, cz), {
        stroke: false, fillColor: previewColor, fillOpacity: 0.5, interactive: false
      }).addTo(paintPreview);
    }

    if (interactiveRegions) {
      map.on('mousedown', function (e) {
        if (!paintMode) { return; }
        painting = true;
        beginStroke();
        var c = chunkAt(e.latlng);
        paintChunk(c.x, c.z);
      });
      map.on('mousemove', function (e) {
        if (!painting) { return; }
        var c = chunkAt(e.latlng);
        paintChunk(c.x, c.z);
      });
      var end = function () {
        if (!painting) { return; }
        painting = false;
        var chunks = Object.keys(stroke).map(function (k) { return stroke[k]; });
        paintPreview.clearLayers();
        stroke = null;
        if (chunks.length) { strokeCbs.forEach(function (cb) { cb(chunks); }); }
      };
      map.on('mouseup', end);
      map.getContainer().addEventListener('mouseleave', end);
    }

    function escapeHtml(s) {
      return String(s).replace(/[&<>"]/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
      });
    }

    return {
      map: map,
      currentWorld: function () { return currentWorld; },
      onWorldChange: function (cb) { worldChangeCbs.push(cb); },
      refreshRegions: refreshRegions,
      refreshPlayers: refreshPlayers,
      onAuthRequired: function (cb) { authRequiredCbs.push(cb); },
      regionData: function () { return regionData; },
      setPaintMode: function (on) {
        paintMode = on;
        if (on) { map.dragging.disable(); } else { map.dragging.enable(); }
      },
      setPreviewColor: function (c) { previewColor = c; },
      onStroke: function (cb) { strokeCbs.push(cb); }
    };
  }

  return { create: create };
})();
