# Mall: CLAUDE.md för ett projekt som använder Spola

Kopiera avsnittet nedan till `CLAUDE.md` i ditt projekt och fyll i det som står inom `<>`.

---

## Spola

Projektet bygger på [Spola](https://github.com/paokarlsson/spola) <version, t.ex. 0.1.0>, ett
Java 25-ramverk som ersätter Spring och Lombok med explicit kod och bara JDK:n.

- Läs `framework/MONSTER.md` i Spola innan du skriver infrastruktur. Där står vilka moduler som
  finns, hur ett Spola-API ser ut och vad som hör hemma i Spola.
- Spola-modulerna ligger som jar-filer i `lib/` och används på modulvägen
  (`requires spola.web;` i `module-info.java`).
- Lägg inte till Spring, Lombok eller andra bibliotek för sådant som Spring eller Lombok
  hade gett projektet. Använd Spola, eller beställ det (se nedan).
- Inga annoteringar utöver JDK:ns, ingen reflektion för wiring och ingen classpath-scanning.
  Koppla ihop allt i vanlig Java-kod.

### När något saknas i Spola
Om projektet behöver något som Spring eller Lombok har men Spola saknar:

1. Skriv ett behov enligt `uppdrag/BEHOV-MALL.md` i Spola och spara det som
   `<sökväg till Spola>/uppdrag/behov/<ÅÅÅÅ-MM-DD>-<namn>.md`. Finns Spola inte lokalt: skapa
   ett GitHub-issue i `paokarlsson/spola` med samma innehåll och etiketten `behov`.
2. Det viktigaste i behovet är användningsfallet, alltså koden du vill kunna skriva.
3. Bygg under tiden en minimal lösning i projektet, i Spolas stil, i ett eget paket
   (`<app>.spolabehov.<namn>`). Den ska vara lätt att byta ut.
4. När Spola har släppt modulen: uppdatera jar-filerna i `lib/`, byt ut den lokala lösningen
   och ta bort paketet.

Det som bara gäller det här projektet, till exempel affärsregler och klienter för ett visst
företags API, hör hemma i projektet och beställs inte.
