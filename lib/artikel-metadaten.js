'use strict';

// Liest ausschliesslich die Kopfdaten eines Artikels: Titel, Autor, Datum,
// Publikation. Das sind die Angaben, die Redaktionen fuer Suchmaschinen und
// Social-Media-Vorschauen offen ausliefern, und genau die Angaben, die man fuer
// eine Titelsuche in der Pressedatenbank braucht.
//
// Der Artikeltext wird bewusst nicht ausgewertet - auch dann nicht, wenn er im
// JSON-LD mitgeliefert wird. Den Text liefert die Bibliotheksdatenbank.

const JSON_LD = /<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;

function metaInhalt(html, attribut, wert) {
  const muster = new RegExp(
    `<meta[^>]+${attribut}=["']${wert}["'][^>]*>`,
    'i'
  );
  const treffer = html.match(muster);
  if (!treffer) return null;
  const inhalt = treffer[0].match(/content=["']([^"']*)["']/i);
  return inhalt ? dekodiere(inhalt[1]) : null;
}

const NAMENS_ENTITAETEN = {
  auml: 'ä', ouml: 'ö', uuml: 'ü', Auml: 'Ä', Ouml: 'Ö', Uuml: 'Ü', szlig: 'ß',
  laquo: '«', raquo: '»', bdquo: '„', ldquo: '“', rdquo: '”', sbquo: '‚',
  lsquo: '‘', rsquo: '’', ndash: '–', mdash: '—', hellip: '…', euro: '€'
};

function dekodiere(text) {
  if (typeof text !== 'string') return '';
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, zahl) => String.fromCodePoint(Number(zahl)))
    .replace(/&([a-z]+);/gi, (treffer, name) => NAMENS_ENTITAETEN[name] || treffer)
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function alsName(wert) {
  if (!wert) return null;
  if (typeof wert === 'string') return dekodiere(wert);
  if (Array.isArray(wert)) {
    const namen = wert.map(alsName).filter(Boolean);
    return namen.length ? namen.join(', ') : null;
  }
  if (typeof wert === 'object' && typeof wert.name === 'string') return dekodiere(wert.name);
  return null;
}

const ARTIKEL_TYPEN = ['Article', 'NewsArticle', 'ReportageNewsArticle', 'Report', 'BlogPosting'];

function sammleObjekte(daten, gesammelt = []) {
  if (Array.isArray(daten)) {
    daten.forEach((eintrag) => sammleObjekte(eintrag, gesammelt));
  } else if (daten && typeof daten === 'object') {
    gesammelt.push(daten);
    if (Array.isArray(daten['@graph'])) sammleObjekte(daten['@graph'], gesammelt);
  }
  return gesammelt;
}

function ausJsonLd(html) {
  const ergebnis = {};
  let treffer;

  JSON_LD.lastIndex = 0;
  while ((treffer = JSON_LD.exec(html)) !== null) {
    let daten;
    try {
      daten = JSON.parse(treffer[1].trim());
    } catch {
      continue; // Fehlerhaftes JSON-LD ist auf Nachrichtenseiten normal.
    }

    for (const objekt of sammleObjekte(daten)) {
      const typ = objekt['@type'];
      const typen = Array.isArray(typ) ? typ : [typ];
      if (!typen.some((t) => ARTIKEL_TYPEN.includes(t))) continue;

      // Nur Kopfdaten - objekt.articleBody wird nicht angefasst.
      ergebnis.titel = ergebnis.titel || alsName(objekt.headline);
      ergebnis.autor = ergebnis.autor || alsName(objekt.author);
      ergebnis.publikation = ergebnis.publikation || alsName(objekt.publisher);
      ergebnis.datum = ergebnis.datum || (typeof objekt.datePublished === 'string' ? objekt.datePublished : null);
    }
  }

  return ergebnis;
}

function normalisiereDatum(rohdatum) {
  if (!rohdatum) return null;
  const datum = new Date(rohdatum);
  if (Number.isNaN(datum.getTime())) return null;
  return datum.toISOString().slice(0, 10);
}

function extrahiereMetadaten(html) {
  const quelle = typeof html === 'string' ? html : '';
  const ldDaten = ausJsonLd(quelle);

  const titelTag = quelle.match(/<title[^>]*>([\s\S]*?)<\/title>/i);

  const titel =
    ldDaten.titel ||
    metaInhalt(quelle, 'property', 'og:title') ||
    metaInhalt(quelle, 'name', 'twitter:title') ||
    (titelTag ? dekodiere(titelTag[1]) : null);

  const autor =
    ldDaten.autor ||
    metaInhalt(quelle, 'name', 'author') ||
    metaInhalt(quelle, 'property', 'article:author');

  const publikation =
    ldDaten.publikation ||
    metaInhalt(quelle, 'property', 'og:site_name');

  const datum = normalisiereDatum(
    ldDaten.datum ||
    metaInhalt(quelle, 'property', 'article:published_time') ||
    metaInhalt(quelle, 'name', 'date') ||
    metaInhalt(quelle, 'itemprop', 'datePublished')
  );

  return { titel: titel || null, autor: autor || null, publikation: publikation || null, datum };
}

module.exports = { extrahiereMetadaten, dekodiere };
