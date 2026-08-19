'use strict';

// Zuordnung Bibliothek -> GENIOS-Instanz.
// Die Domains stammen aus dem BibBot-Projekt (stefanw/bibbot, GPL-3.0),
// das dieselben Zugaenge automatisiert.
const BIBLIOTHEKEN = [
  { id: 'voebb', name: 'VÖBB Berlin', domain: 'bib-voebb.genios.de' },
  { id: 'muenchen', name: 'Münchner Stadtbibliothek', domain: 'bib-muenchen.genios.de' },
  { id: 'hamburg', name: 'Bücherhallen Hamburg', domain: 'buecherhallen.genios.de' },
  { id: 'stuttgart', name: 'Stadtbibliothek Stuttgart', domain: 'bib-stuttgart.genios.de' },
  { id: 'leipzig', name: 'Städtische Bibliotheken Leipzig', domain: 'stadtbib-leipzig.genios.de' },
  { id: 'dresden', name: 'Städtische Bibliotheken Dresden (eBibo)', domain: 'sbdresden.genios.de' },
  { id: 'nuernberg', name: 'Stadtbibliothek Nürnberg', domain: 'bib-nuernberg.genios.de' },
  { id: 'essen', name: 'Stadtbibliothek Essen', domain: 'bib-essen.genios.de' },
  { id: 'braunschweig', name: 'Stadtbibliothek Braunschweig', domain: 'bib-braunschweig.genios.de' },
  { id: 'chemnitz', name: 'Stadtbibliothek Chemnitz', domain: 'bib-chemnitz.genios.de' },
  { id: 'darmstadt', name: 'Stadtbibliothek Darmstadt', domain: 'bib-darmstadt.genios.de' },
  { id: 'erfurt', name: 'Stadt- und Regionalbibliothek Erfurt', domain: 'bib-erfurt.genios.de' },
  { id: 'halle', name: 'Stadtbibliothek Halle', domain: 'bib-halle.genios.de' },
  { id: 'jena', name: 'Ernst-Abbe-Bücherei Jena', domain: 'bib-jena.genios.de' },
  { id: 'potsdam', name: 'Stadt- und Landesbibliothek Potsdam', domain: 'bib-potsdam.genios.de' },
  { id: 'oberhausen', name: 'Stadtbibliothek Oberhausen', domain: 'bib-oberhausen.genios.de' },
  { id: 'bayern', name: 'Bibliotheksverbund Bayern', domain: 'bib-bayern.genios.de' },
  { id: 'bawue', name: 'Bibliotheksverbund Baden-Württemberg', domain: 'bib-bawue.genios.de' },
  { id: 'oberlausitz', name: 'Bibliotheksverbund Oberlausitz', domain: 'bib-oberlausitz.genios.de' },
  { id: 'ostschweiz', name: 'Bibliotheksverbund Ostschweiz / Liechtenstein', domain: 'bib-ostschweiz.genios.de' },
  { id: 'wiso', name: 'WISO (wiso-net.de)', domain: 'www.wiso-net.de' }
];

// Zeitungsnamen, die Redaktionen an den Seitentitel haengen. Im Datenbanktitel
// stehen sie nicht, deshalb wuerde die Suche mit ihnen ins Leere laufen.
const TITEL_ANHAENGSEL = [
  'DER SPIEGEL', 'SPIEGEL ONLINE', 'ZEIT ONLINE', 'DIE ZEIT', 'WELT', 'DIE WELT',
  'FAZ.NET', 'Süddeutsche Zeitung', 'SZ.de', 'Tagesspiegel', 'Handelsblatt',
  'WirtschaftsWoche', 'Berliner Zeitung', 'Hamburger Abendblatt', 'heise online',
  'STERN.de', 'manager magazin', 'NZZ', 'DER STANDARD', 'kurier.at', 'Business Insider'
];

const TRENNER = /\s+[|–—-]\s+/;

function bereinigeTitel(titel) {
  if (typeof titel !== 'string') return '';
  let sauber = titel.replace(/\s+/g, ' ').trim();

  // Nur das letzte Segment pruefen: "Überschrift - DER SPIEGEL" wird gekuerzt,
  // "Rot-Grün-Debatte" bleibt unangetastet.
  const teile = sauber.split(TRENNER);
  if (teile.length > 1) {
    const letztes = teile[teile.length - 1].trim();
    const istAnhaengsel = TITEL_ANHAENGSEL.some(
      (name) => letztes.toLowerCase() === name.toLowerCase()
    );
    if (istAnhaengsel) {
      sauber = teile.slice(0, -1).join(' - ').trim();
    }
  }

  return sauber;
}

function findeBibliothek(id) {
  return BIBLIOTHEKEN.find((b) => b.id === id) || null;
}

// GENIOS-Suchadresse. Die Anmeldung passiert im Browser mit dem eigenen
// Bibliotheksausweis - dieser Server sieht keine Zugangsdaten.
function sucheUrl(domain, titel) {
  const suchbegriff = bereinigeTitel(titel);
  if (!domain || !suchbegriff) return null;
  if (!/^[a-z0-9.-]+$/i.test(domain)) return null;

  const params = new URLSearchParams({ requestText: suchbegriff });
  return `https://${domain}/searchResult/Alle?${params.toString()}`;
}

module.exports = { BIBLIOTHEKEN, bereinigeTitel, findeBibliothek, sucheUrl };
